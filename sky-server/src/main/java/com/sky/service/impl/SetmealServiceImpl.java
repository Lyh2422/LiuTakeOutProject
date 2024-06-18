package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.SetmealDish;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.exception.SetmealEnableFailedException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.result.PageResult;
import com.sky.service.SetmealService;
import com.sky.vo.SetmealVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
public class SetmealServiceImpl implements SetmealService {

    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private SetmealDishMapper  setmealDishMapper;
    @Autowired
    private DishMapper dishMapper;

    public List<Setmeal> list(Setmeal setmeal) {
        List<Setmeal> list=setmealMapper.list(setmeal);
        return list;
    }

    /**
     * 新增套餐，同时需要保存套餐和菜品的关系
     * @param setmealDTO
     */
    @Transactional
    @Override
    public void saveWithDish(SetmealDTO setmealDTO) {
        //请求参数用的是SetmealDTO类封装的，包含套餐数据以及套餐菜品关系表数据
        //插入套餐的基本数据进行属性拷贝
        Setmeal setmeal=new Setmeal();
        BeanUtils.copyProperties(setmealDTO,setmeal);

        //想套套餐表中插入数据
        setmealMapper.insert(setmeal);

        //获取生成的套餐id  通过sql中的useGeneratedKeys="true" keyProperty="id" 获取插入后生成的主键值
        //套餐菜品关系表的setmealId页面不能传递，它是向套餐表插入数据之后生成的主键值 也就是套餐菜品关系表的逻辑外键setmealId
        Long setmealId=setmeal.getId();

        //获取页面传来的套餐和菜品关系表数据
        List<SetmealDish> setmealDishes=setmealDTO.getSetmealDishes();
        //遍历关系表数据，为关系表中的每一条数据(每一个对象)的setmealId赋值
        //这个地方不需要像之前写新增菜品时写多个if判断，因为之前的口味数据是非必须的。
        //这个地方要求套餐必须包含菜品，所以不需要if判断，不存在套餐不包含菜品
        setmealDishes.forEach(setmealDish -> {
            //将Setmeal套餐类的id属性赋值给SetmealDish套餐关系类的setmealId
            //套餐表的id保存在套餐关系表充当外键为setmealId
            setmealDish.setSetmealId(setmealId);
        });

        //保存套餐和菜品的关联关系  动态sql批量插入
        setmealDishMapper.insertBatch(setmealDishes);
    }

    /**
     * 套餐起售、停售
     * @param status
     * @param id
     */
    @Override
    public void startOrStop(Integer status, Long id) {
        //起售套餐时，判断套餐内是否有停售菜品，有停售菜品提示"套餐内包未起售菜品，无法起售"
        if(status.equals(StatusConstant.ENABLE)){
            List<Dish> dishList=dishMapper.getBySetmealId(id);
            if(dishList!=null && dishList.size()>0){
                dishList.forEach(dish->{
                    if(StatusConstant.DISABLE.equals(dish.getStatus())){
                        throw new SetmealEnableFailedException(MessageConstant.SETMEAL_ENABLE_FAILED);
                    }
                });
            }
        }
        Setmeal setmeal=Setmeal.builder()
                .id(id)
                .status(status)
                .build();
        setmealMapper.update(setmeal);
    }

    /**
     * 分页查询
     * @param setmealPageQueryDTO
     * @return
     */
    @Override
    public PageResult pageQuery(SetmealPageQueryDTO setmealPageQueryDTO) {
        int pageNum=setmealPageQueryDTO.getPage();
        int pageSize=setmealPageQueryDTO.getPageSize();

        //需要在查询功能之前开启分页功能：当前页码，每页显示的条数
        PageHelper.startPage(pageNum,pageSize);
        //该方法返回值为page对象，里面保存的是分页之后的相关数据
        Page<SetmealVO> page=setmealMapper.pageQuery(setmealPageQueryDTO);
        //封装到PageResult中：总记录数  当前页数数据集合
        return new PageResult(page.getTotal(),page.getResult());
    }

    /**
     * 批量删除套餐
     * @param ids
     */
    @Transactional
    @Override
    public void deleteBatch(List<Long> ids) {
        //判断当前套餐是否能够删除--是否存在起售中的套餐？
        //思路：遍历获取传入的id  根据id查询setmeal中的status  0停售 1起售
        //如果是1代表是起售状态不能删除
        ids.forEach(id ->{
            Setmeal setmeal=setmealMapper.getById(id);
            if(StatusConstant.ENABLE==setmeal.getStatus()){
                //起售中的套餐不能删除
                throw new DeletionNotAllowedException(MessageConstant.SETMEAL_ON_SALE);
            }
        });

        //思路：套餐表和菜品表是多对多关系 把整个套餐都删除了，那么关系表中保存的套餐对应的菜品关系就没有意义了，所以此时也应该删除关系表中的数据。
        ids.forEach(setmealId->{
            //删除套餐表中的数据
            setmealMapper.deleteById(setmealId);
            //删除套餐菜品关系表中的数据
            setmealDishMapper.deleteBySetmealId(setmealId);
        });
    }

    /**
     * 根据id查询套餐和套餐菜品关系
     * @param id
     * @return
     */
    @Override
    public SetmealVO getByIdWithDish(Long id) {
        //根据id查询套餐表信息
        Setmeal setmeal=setmealMapper.getById(id);
        //根据id查询套餐菜品关系表数据
        List<SetmealDish> setmealDishes=setmealDishMapper.getBySetmealId(id);

        //封装返回结果
        SetmealVO setmealVO=new SetmealVO();
        BeanUtils.copyProperties(setmeal,setmealVO);
        setmealVO.setSetmealDishes(setmealDishes);

        return setmealVO;
    }

    /**
     * 修改套餐
     * @param setmealDTO
     */
    @Override
    @Transactional
    public void update(SetmealDTO setmealDTO) {
        Setmeal setmeal=new Setmeal();
        BeanUtils.copyProperties(setmealDTO,setmeal);

        //1.修改套餐表,执行update语句
        setmealMapper.update(setmeal);

        //获取生成的他套餐id    t通过sql中useGeneratedKeys="true" keyProperty="id" 获取插入后生成的主键值
        Long setmealId=setmealDTO.getId();

        //2.删除套餐和菜品的关联数据    操作setmeal_dish表 执行delete
        setmealDishMapper.deleteBySetmealId(setmealId);

        //获取页面传来的套餐和菜品关系表数据
        List<SetmealDish> setmealDishes=setmealDTO.getSetmealDishes();

        setmealDishes.forEach(setmealDish -> {
            setmealDish.setSetmealId(setmealId);
        });

        //重新插入套餐和菜品的关联关系，操作setmeal_dish表，执行insert
        //动态sql批量插入
        setmealDishMapper.insertBatch(setmealDishes);
    }
}

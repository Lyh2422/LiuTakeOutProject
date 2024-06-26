package com.sky.service.impl;

import com.alibaba.druid.sql.PagerUtils;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WeChatPayUtil weChatPayUtil;
    @Autowired
    private WebSocketServer webSocketServer;

    /**
     * 用户下单
     * @param ordersSubmitDTO
     * @return
     */
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        //1.处理各种业务异常(地址簿为空，购物车数据为空)
        AddressBook addressBook=addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if(addressBook==null){
            //抛出业务异常
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }


        //查询当前用户的购物车数据
        Long userId= BaseContext.getCurrentId();

        ShoppingCart shoppingCart=new ShoppingCart();
        shoppingCart.setUserId(userId);
        List<ShoppingCart> shoppingCartList=shoppingCartMapper.list(shoppingCart);

        if(shoppingCartList ==null || shoppingCartList.size()==0){
            //抛出业务异常
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        //2.向订单表中插入1条数据
        Orders orders=new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO,orders);
        orders.setOrderTime(LocalDateTime.now());
        orders.setPayStatus(Orders.UN_PAID);
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setNumber(String.valueOf(System.currentTimeMillis()));
        orders.setPhone(addressBook.getPhone());
        orders.setConsignee(addressBook.getConsignee());
        orders.setUserId(userId);

        orderMapper.insert(orders);
        List<OrderDetail> orderDetailList=new ArrayList<>();
        //3.向订单明细表中插入n条数据
        for(ShoppingCart cart : shoppingCartList){
            OrderDetail orderDetail=new OrderDetail();//订单明细
            BeanUtils.copyProperties(cart,orderDetail);
            orderDetail.setOrderId(orders.getId());//设置当前订单明细关联的订单id
            orderDetailList.add(orderDetail);
        }

        orderDetailMapper.insertBatch(orderDetailList);

        //4.清空当前用户的购物车数据
        shoppingCartMapper.deleteByUserId(userId);

        //5.封装VO返回结果
        OrderSubmitVO orderSubmitVO =OrderSubmitVO.builder()
                .id(orders.getId())
                .orderTime(orders.getOrderTime())
                .orderNumber(orders.getNumber())
                .orderAmount(orders.getAmount())
                .build();

        return orderSubmitVO;
    }

    /**
     * 查询订单详情
     * @param id
     * @return
     */
    public OrderVO details(Long id) {
        //根据id查询订单
        Orders orders=orderMapper.getById(id);

        //查询该订单对应的菜品/套餐明细
        List<OrderDetail> orderDetailList=orderDetailMapper.getByOrderId(orders.getId());

        //将该订单及其详情封装到OrderVO并返回
        OrderVO orderVO=new OrderVO();
        BeanUtils.copyProperties(orders,orderVO);
        orderVO.setOrderDetailList(orderDetailList);

        return orderVO;
    }

    /**
     * 订单支付
     * @param ordersPaymentDTO
     * @return
     * @throws Exception
     */
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);

        //调用微信支付接口，生成预支付交易单
        JSONObject jsonObject = weChatPayUtil.pay(
                ordersPaymentDTO.getOrderNumber(), //商户订单号
                new BigDecimal(0.01), //支付金额，单位 元
                "苍穹外卖订单", //商品描述
                user.getOpenid() //微信用户的openid
        );

        if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
            throw new OrderBusinessException("该订单已支付");
        }

        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));

        return vo;
    }

    /**
     * 支付成功，修改订单状态
     * @param outTradeNo
     */
    public void paySuccess(String outTradeNo) {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();

        // 根据订单号查询当前用户的订单
        Orders ordersDB = orderMapper.getByNumberAndUserId(outTradeNo, userId);

        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();

        orderMapper.update(orders);

        //通过WebSocket向客户端浏览器推送消息
        webSocketServer.sendTOAllClient(JSON.toJSONString(orders));
    }

    /**
     * 用户端订单分页查询
     * @param pageNum
     * @param pageSize
     * @param status
     * @return
     */
    public PageResult pageQuery4User(int pageNum, int pageSize, Integer status) {
        //设置分页
        PageHelper.startPage(pageNum,pageSize);

        OrdersPageQueryDTO ordersPageQueryDTO=new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        ordersPageQueryDTO.setStatus(status);

        //分页条件查询
        Page<Orders> page= orderMapper.pageQuery(ordersPageQueryDTO);

        List<Orders> list=new ArrayList();

        //查询出订单明细，并封装如OrderVO进行响应
        if(page !=null &&page.getTotal()>0){
            for(Orders orders:page){
                Long orderId=orders.getId();//订单id

                //查询订单明细
                List<OrderDetail> orderDetailList=orderDetailMapper.getByOrderId(orderId);

                OrderVO orderVO=new OrderVO();
                BeanUtils.copyProperties(orders,orderVO);
                orderVO.setOrderDetailList(orderDetailList);
                list.add(orderVO);
            }
        }
        return new PageResult(page.getTotal(),list);
    }

    /**
     * 用户通过id取消订单
     * @param id
     */
    @Override
    public void userCancelById(Long id) {
        //根据id查询订单
        Orders ordersDB=orderMapper.getById(id);

        //校验了订单是否存在
        if(ordersDB ==null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        //订单状态 1代付款 2待接单 3已接单 4派送中 5已完成 6已取消
        if(ordersDB.getStatus()>2){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        Orders orders=new Orders();
        orders.setId(ordersDB.getId());

        //订单处于待接单状态下取消，需要进行退款
        if(ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)){
            orders.setPayStatus(Orders.CANCELLED);
        }

        //更新订单状态，取消原因，取消时间
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason("用户取消");
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    /**
     * 再来一单
     * @param id
     */
    @Override
    public void repetition(Long id) {
            //查询当前用户id
            Long userId=BaseContext.getCurrentId();

            //根据订单id查询当前订单详情
            List<OrderDetail> orderDetailList=orderDetailMapper.getByOrderId(id);

            //将订单详情对象转换为购物车对象
            List<ShoppingCart> shoppingCartList=orderDetailList.stream().map(x ->{
                ShoppingCart shoppingCart=new ShoppingCart();

                //将原订单详情里面的菜品信息重新赋值到购物车对象中
                //id参数表示要忽略拷贝的属性名，在这里是将OrderDetail对象的属性值拷贝到ShoppingCart对象中，但忽略id属性
                BeanUtils.copyProperties(x,shoppingCart,"id");
                shoppingCart.setUserId(userId);
                shoppingCart.setCreateTime(LocalDateTime.now());

                return  shoppingCart;
            }).collect(Collectors.toList());

            //将购物车对象批量添加到数据库
            shoppingCartMapper.insertBatch(shoppingCartList);
    }

    /**
     * 订单搜索
     * @param ordersPageQueryDTO
     * @return
     */
    @Override
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(),ordersPageQueryDTO.getPageSize());

        Page<Orders> page=orderMapper.pageQuery(ordersPageQueryDTO);

        //部分订单状态，需要额外返回订单菜品信息，将Order转化为OrderVO
        List<OrderVO> orderVOList=getOrderVOList(page);

        return new PageResult(page.getTotal(),orderVOList);
    }

    /**
     * 返回订单菜品信息
     * @param page
     * @return
     */
    private List<OrderVO> getOrderVOList(Page<Orders> page){
        //需要返回订单的菜品信息，自定义OrderVO响应结果
        List<OrderVO> orderVOList=new ArrayList<>();

        List<Orders> ordersList=page.getResult();
        if(!CollectionUtils.isEmpty(ordersList)){
            for(Orders orders :ordersList){
                //将共同字段复制到OrderVO
                OrderVO orderVO=new OrderVO();
                BeanUtils.copyProperties(orders,orderVO);
                String orderDishes=getOrderDishesStr(orders);

                //将订单彩票信息封装到orderVO中，并添加到orderVOList
                orderVO.setOrderDishes(orderDishes);
                orderVOList.add(orderVO);
            }
        }
        return orderVOList;
    }

    /**
     * 根据id获取菜品信息字符串
     * @param orders
     * @return
     */
    private String getOrderDishesStr(Orders orders){
        //查询订单菜品详细信息(订单中的菜品和数量)
        List<OrderDetail> orderDetailList=orderDetailMapper.getByOrderId(orders.getId());

        //将每一条订单菜品信息拼接为字符串(格式：宫保鸡丁*3)
        List<String> orderDishList=orderDetailList.stream().map(x ->{
            String orderDish =x.getName() +"*" +x.getNumber() +";";
            return orderDish;
        }).collect(Collectors.toList());

        //将该订单对应的所有菜品信息拼接在一起
        return String.join("",orderDishList);
    }

    /**
     * 各个订单状态的数量统计
     * @return
     */
    @Override
    public OrderStatisticsVO statistics() {
        //根据状态，分别查询出待接单、待派送、派送中、已完成、已取消的订单数量
        //待接单
        Integer toBeConfirmed= orderMapper.countStatus(Orders.TO_BE_CONFIRMED);
        //待派送
        Integer confirmed=orderMapper.countStatus(Orders.CONFIRMED);
        //派送中
        Integer deliveryInProgress=orderMapper.countStatus(Orders.DELIVERY_IN_PROGRESS);
        //已完成
        Integer completed=orderMapper.countStatus(Orders.COMPLETED);
        //已取消
        Integer cancelled=orderMapper.countStatus(Orders.CANCELLED);

        //将查询出的数据封装到orderStatisticsVO中响应
        OrderStatisticsVO orderStatisticsVO=new OrderStatisticsVO();
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);
        orderStatisticsVO.setConfirmed(confirmed);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);
        orderStatisticsVO.setCompleted(completed);
        orderStatisticsVO.setCancelled(cancelled);

        return orderStatisticsVO;
    }

    /**
     * 接单
     * @param ordersConfirmDTO
     */
    @Override
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        Orders orders=Orders.builder()
                .id(ordersConfirmDTO.getId())
                .status(Orders.CONFIRMED)
                .build();
        orderMapper.update(orders);
    }

    /**
     * 拒单
     * @param ordersRejectionDTO
     */
    @Override
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) {
        //根据id查询订单
        Orders ordersDB=orderMapper.getById(ordersRejectionDTO.getId());

        //订单只有存在且状态为2(待接单)才可以拒单
        if(ordersDB ==null || !ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        //支付状态
        Integer payStatus = ordersDB.getPayStatus();
        // TODO  没有实现支付功能可以先注释
        if(payStatus ==Orders.PAID){
            //用户已支付，需要退款
            log.info("商家申请退款------");
        }

        //拒单需要退款，根据订单id更新订单状态，拒单原因、取消时间
        Orders orders=new Orders();
        orders.setId(ordersDB.getId());
        ordersDB.setStatus(Orders.CANCELLED);
        ordersDB.setRejectionReason(ordersRejectionDTO.getRejectionReason());
        ordersDB.setCancelTime(LocalDateTime.now());

        orderMapper.update(orders);

    }

    /**
     * 取消订单
     * @param ordersCancelDTO
     */
    @Override
    public void cancel(OrdersCancelDTO ordersCancelDTO) {
        //根据id查询订单
        Orders ordersDB=orderMapper.getById(ordersCancelDTO.getId());

        //支付状态
        Integer payStatus= ordersDB.getPayStatus();
        if(payStatus==1){
            //用户已支付，需要退款
            log.info("商家申请退款-------");
        }

        //管理端取消订单需要退款，根据订单id更新订单状态、取消原因、取消时间
        Orders orders=new Orders();
        orders.setId(ordersCancelDTO.getId());
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason(ordersCancelDTO.getCancelReason());
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    /**
     * 派送订单
     * @param id
     */
    @Override
    public void delivery(Long id) {
        //根据id查询订单
        Orders ordersDB=orderMapper.getById(id);

        //校验订单是否存在，并且状态为3
        if(ordersDB ==null || !ordersDB.getStatus().equals(Orders.CONFIRMED)){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders=new Orders();
        orders.setId(ordersDB.getId());
        //更新订单状态，状态转为派送中
        orders.setStatus(Orders.DELIVERY_IN_PROGRESS);
    }

    @Override
    public void complete(Long id) {
        //根据id查询订单
        Orders ordersDB=orderMapper.getById(id);

        //校验订单是否存在，并且状态为4
        if(ordersDB ==null || !ordersDB.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders=new Orders();
        orders.setId(ordersDB.getId());
        //更新订单状态，状态转为派送中
        orders.setStatus(Orders.COMPLETED);
        orders.setDeliveryTime(LocalDateTime.now());

        orderMapper.update(orders);
    }


    /**
     * 客户催单
     * @param id
     */
    @Override
    public void reminder(Long id) {
        //根据id查询订单
        Orders ordersDB=orderMapper.getById(id);

        //校验订单是否存在
        if(ordersDB ==null){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Map map=new HashMap();
        map.put("type",2);
        map.put("orderId",id);
        map.put("content","订单号: "+ordersDB.getNumber());

        //通过WebSocket向客户端浏览器推送消息
        webSocketServer.sendTOAllClient(JSON.toJSONString(map));
    }
}

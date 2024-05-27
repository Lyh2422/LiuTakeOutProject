package com.sky.mapper;


import com.sky.entity.AddressBook;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AddressBookMapper {
    /**
     * 动态查询
     * @param addressBook
     * @return
     */
    List<AddressBook> list(AddressBook addressBook);

    /**
     * 新增
     * @param addressBook
     */
    @Insert("insert into address_book "+
    "(user_id,consignee,phone,sex,province_code,province_name,city_code,city_name,district_code," +
     "district_name,detail,label,is_default)"+
    " values (#{userId},#{consignee},#{phone},#{sex},#{provinceCode},#{provinceName},#{cityCode}," +
             "#{cityName},#{districtCode},#{districtName},#{detail},#{label},#{isDefault})")
    void insert(AddressBook addressBook);


    @Select("select * from address_book where id=#{id}")
    AddressBook getById(Long id);

    /**
     * 根据id修改
     * @param addressBook
     */
    void update(AddressBook addressBook);

    @Update("update address_book set is_default =#{isDefault} where user_id =#{userId}")
    void updateIsDefaultByUserId(AddressBook addressBook);

    @Delete("delete from address_book where id=#{id}")
    void deleteById(Long id);
}

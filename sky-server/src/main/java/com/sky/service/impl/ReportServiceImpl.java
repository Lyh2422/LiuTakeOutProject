package com.sky.service.impl;

import com.aliyun.oss.common.utils.StringUtils;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.vo.TurnoverReportVO;
import com.sky.vo.UserReportVO;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import org.apache.poi.util.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ReportServiceImpl implements ReportService {


    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private UserMapper userMapper;

    /**
     * 统计指定时间区间内的营业额数据
     * @param begin
     * @param end
     * @return
     */
    public TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end) {
        //当前集合用于存放begin~end范围内的每天的日期
        List<LocalDate> dateList=new ArrayList<>();

        dateList.add(begin);

        while(!begin.equals(end)){
            //日期计算  计算指定日期的后一天对应的日期
            begin=begin.plusDays(1);
            dateList.add(begin);
        }

        //存放每天的营业额
        List<Double> turnoverList=new ArrayList<>();
        for (LocalDate date : dateList) {
            //查询Date日期对应的营业额数据，营业额是指：状态为"已完成"的订单金额合计
            LocalDateTime beginTime=LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime=LocalDateTime.of(date, LocalTime.MAX);

            //select sum(amount) from orders where order_time >beginTime and order_time <endTime and status=5
            Map map=new HashMap();
            map.put("begin",beginTime);
            map.put("end",endTime);
            map.put("status", Orders.COMPLETED);
            Double turnover=orderMapper.sumByMap(map);
            turnoverList.add(turnover);
        }
        return TurnoverReportVO
                .builder()
                .dateList(StringUtils.join(String.valueOf(dateList),","))
                .turnoverList(StringUtils.join(String.valueOf(turnoverList),","))
                .build();
    }


    /**
     * 统计指定时间区间内的用户数据
     * @param begin
     * @param end
     * @return
     */
    public UserReportVO getUserStatistics(LocalDate begin, LocalDate end) {
        //查询从begin~end 之间的每天对应的日期
        List<LocalDate> dateList=new ArrayList<>();

        dateList.add(begin);

        while (!begin.equals(end)){
            begin=begin.plusDays(1);
            dateList.add(begin);
        }

        //存放每天的新增用户数量 select count(id) from user where create_time <? and create_time >?
        List<Integer> newUserList=new ArrayList<>();
        //存放每天的总用户数量 select count(id) from user where create_time <?
        List<Integer> totalUserList=new ArrayList<>();

        for (LocalDate date : dateList) {
            LocalDateTime beginTime= LocalDateTime.of(date,LocalTime.MIN);
            LocalDateTime endTime= LocalDateTime.of(date,LocalTime.MAX);

            Map map=new HashMap();
            map.put("end",endTime);

            //总用户数量
            Integer totalUser=userMapper.countByMap(map);

            map.put("begin",beginTime);
            //新增用户数量
            Integer newUser=userMapper.countByMap(map);

            totalUserList.add(totalUser);
            newUserList.add(newUser);
        }

        //封装结果数据
        return UserReportVO
                .builder()
                .dateList(StringUtils.join(String.valueOf(dateList),","))
                .totalUserList(StringUtils.join(String.valueOf(totalUserList),","))
                .newUserList(StringUtil.join(",", newUserList))
                .build();
    }
}

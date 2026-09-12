package com.qiyun.hotel.mapper;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiyun.hotel.domain.entity.DailyInventory;

public interface DailyInventoryMapper extends BaseMapper<DailyInventory> {

    /**
     * 行锁查询：锁定"房型 × 日期"的库存行（见 DailyInventoryMapper.xml 的 FOR UPDATE）。
     *
     * <p>防超卖的关键：下单事务内先锁后校验再扣减，任何并发事务在同一批库存行上串行化；
     * 锁顺序固定为日期升序，避免交叉加锁死锁。
     */
    List<DailyInventory> lockByTypeAndDates(@Param("roomTypeId") Long roomTypeId,
                                            @Param("dates") List<LocalDate> dates);

    /**
     * 乐观扣减（条件原子更新）：单条 SQL 内完成"校验+扣减"，无锁等待。
     * 仅当扣减后不超卖才生效；返回受影响行数（0 = 房量不足）。
     * 多晚订单：逐晚执行，任一行失败由事务整体回滚保证一致性。
     */
    int tryDeduct(@Param("id") Long id, @Param("count") int count);
}

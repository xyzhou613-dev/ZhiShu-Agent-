package com.bemodel.simulation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bemodel.simulation.entity.SimulationRun;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SimulationRunMapper extends BaseMapper<SimulationRun> {
}

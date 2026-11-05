package com.bemodel.modeling.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.modeling.entity.Axiom;
import com.bemodel.modeling.mapper.AxiomMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AxiomService extends ServiceImpl<AxiomMapper, Axiom> {

    public List<Axiom> listAll() {
        return list(new LambdaQueryWrapper<Axiom>().orderByAsc(Axiom::getAxiomCode));
    }
}

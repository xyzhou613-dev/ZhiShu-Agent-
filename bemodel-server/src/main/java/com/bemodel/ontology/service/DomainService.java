package com.bemodel.ontology.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.ontology.entity.Domain;
import com.bemodel.ontology.mapper.DomainMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DomainService extends ServiceImpl<DomainMapper, Domain> {

    public List<Domain> listAll() {
        return lambdaQuery().orderByAsc(Domain::getSort).list();
    }
}

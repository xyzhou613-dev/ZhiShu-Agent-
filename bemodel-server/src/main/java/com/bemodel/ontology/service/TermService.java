package com.bemodel.ontology.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.ontology.entity.Term;
import com.bemodel.ontology.mapper.TermMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TermService extends ServiceImpl<TermMapper, Term> {

    public List<Term> listAll(String conceptCode) {
        return lambdaQuery()
                .eq(conceptCode != null && !conceptCode.isBlank(), Term::getConceptCode, conceptCode)
                .list();
    }
}

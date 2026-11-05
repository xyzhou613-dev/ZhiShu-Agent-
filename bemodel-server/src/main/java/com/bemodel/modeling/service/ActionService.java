package com.bemodel.modeling.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.common.BizException;
import com.bemodel.common.StateMachine;
import com.bemodel.modeling.entity.Action;
import com.bemodel.modeling.mapper.ActionMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ActionService extends ServiceImpl<ActionMapper, Action> {

    public List<Action> list(String conceptCode) {
        return lambdaQuery()
                .eq(conceptCode != null && !conceptCode.isBlank(), Action::getConceptCode, conceptCode)
                .orderByAsc(Action::getActionCode)
                .list();
    }

    public Action create(Action action) {
        if (lambdaQuery().eq(Action::getActionCode, action.getActionCode()).count() > 0) {
            throw new BizException("动作编码已存在: " + action.getActionCode());
        }
        action.setStatus("DRAFT");
        action.setVersion(1);
        save(action);
        return action;
    }

    public Action transition(String actionCode, String target) {
        Action action = lambdaQuery().eq(Action::getActionCode, actionCode).one();
        if (action == null) {
            throw new BizException("动作不存在: " + actionCode);
        }
        StateMachine.check(action.getStatus(), target);
        action.setStatus(target);
        if ("PUBLISHED".equals(target)) {
            action.setVersion(action.getVersion() + 1);
        }
        updateById(action);
        return action;
    }
}

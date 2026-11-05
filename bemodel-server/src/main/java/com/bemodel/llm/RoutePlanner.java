package com.bemodel.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 路由决策纯函数（借鉴 4，golden 直测）：零 IO 零时钟——开路布尔由 chat() 经
 * CircuitState.isUsable(now) 预先算好传入。规则（spec §4）：
 * callPrimaryRoute 覆盖优先（只认 "backup"，其余值=默认主先）；开路端点在另一候选可用时跳过；
 * 两路全开=原序返回（死马当活马医）；备路不存在退化为单候选。
 */
final class RoutePlanner {

    private RoutePlanner() {
    }

    static List<ProviderEndpoint> plan(String callType, ProviderEndpoint primary, ProviderEndpoint backup,
                                       Map<String, String> callPrimaryRoute, boolean primaryOpen, boolean backupOpen) {
        List<ProviderEndpoint> order = new ArrayList<>(2);
        if (primary != null) {
            order.add(primary);
        }
        if (backup != null) {
            order.add(backup);
        }
        String override = callPrimaryRoute == null ? null : callPrimaryRoute.get(callType);
        if ("backup".equals(override) && order.contains(backup)) {
            order.remove(backup);
            order.add(0, backup);
        }
        List<ProviderEndpoint> usable = new ArrayList<>(2);
        for (ProviderEndpoint ep : order) {
            if (!(ep == backup ? backupOpen : primaryOpen)) {
                usable.add(ep);
            }
        }
        return usable.isEmpty() ? order : usable;
    }
}

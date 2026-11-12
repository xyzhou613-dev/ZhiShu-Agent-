package com.bemodel.compute;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个算力接口需要的操作权限（M-16）。可放在方法或控制器类上。
 *
 * <p>算力接口没有声明该注解时，{@link ComputeAccessInterceptor} 一律拒绝，避免新增接口漏配权限。
 * 权限目录只维护在 {@link ComputeOperation}，不在每个控制器里各写一份角色列表。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@interface ComputeAccess {
    ComputeOperation value();
}

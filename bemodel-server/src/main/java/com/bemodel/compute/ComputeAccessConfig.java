package com.bemodel.compute;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * M-16 统一权限入口：所有算力接口走同一个拦截器，新增控制器不再单独复制 URL 角色规则。
 */
@Configuration
@Profile("compute")
class ComputeAccessConfig implements WebMvcConfigurer {
    private final ComputeAccessInterceptor interceptor;

    ComputeAccessConfig(ComputeAccessInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/compute/**");
    }
}

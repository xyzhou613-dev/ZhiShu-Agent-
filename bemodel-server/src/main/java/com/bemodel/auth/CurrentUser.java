package com.bemodel.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Locale;

/**
 * 当前请求认证主体的快捷访问（JWT filter 已写入 SecurityContext）。
 * 供服务层做角色裁决（如概念发布/本体版本的评审门禁），与 URL 层 SecurityConfig 互为纵深。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /** 当前登录名（JWT subject）；无认证主体时返回 null */
    public static String username() {
        Authentication a = authentication();
        if (a == null || a.getPrincipal() == null) {
            return null;
        }
        return "anonymousUser".equals(String.valueOf(a.getPrincipal())) ? null : String.valueOf(a.getPrincipal());
    }

    /** 是否持有任一角色（传入不带 ROLE_ 前缀，如 hasAnyRole("REVIEWER","ADMIN")） */
    public static boolean hasAnyRole(String... roles) {
        Authentication a = authentication();
        if (a == null) {
            return false;
        }
        for (String role : roles) {
            String expected = ("ROLE_" + role).toUpperCase(Locale.ROOT);
            if (a.getAuthorities().stream().anyMatch(g -> expected.equals(String.valueOf(g.getAuthority()).toUpperCase(Locale.ROOT)))) {
                return true;
            }
        }
        return false;
    }
}

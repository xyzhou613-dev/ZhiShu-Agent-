package com.bemodel.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.auth.mapper.PlatformUserMapper;
import com.bemodel.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final PlatformUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public Map<String, Object> login(String username, String password) {
        PlatformUser user = userMapper.selectOne(new LambdaQueryWrapper<PlatformUser>()
                .eq(PlatformUser::getUsername, username).last("LIMIT 1"));
        if (user == null || password == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BizException("用户名或密码错误");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", jwtService.issue(user));
        result.put("username", user.getUsername());
        result.put("displayName", user.getDisplayName());
        result.put("role", user.getRole());
        return result;
    }

    /** 当前登录用户（JWT filter 已写入 SecurityContext；到此必有认证主体） */
    public Map<String, Object> me() {
        String username = String.valueOf(SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        PlatformUser user = userMapper.selectOne(new LambdaQueryWrapper<PlatformUser>()
                .eq(PlatformUser::getUsername, username).last("LIMIT 1"));
        if (user == null) {
            throw new BizException("用户不存在: " + username);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", user.getUsername());
        result.put("displayName", user.getDisplayName());
        result.put("role", user.getRole());
        return result;
    }
}

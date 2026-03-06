package com.codeguardian.service;

import cn.dev33.satoken.stp.StpUtil;
import com.codeguardian.dto.LoginRequestDTO;
import com.codeguardian.dto.LoginResponseDTO;
import com.codeguardian.entity.User;
import com.codeguardian.repository.UserRepository;
import com.codeguardian.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @description: 认证服务
 * @author: Winston
 * @date: 2026/2/27 15:48
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private BCryptPasswordEncoder bCryptPasswordEncoder;


    /**
     * 用户登录
     *
     * <p>根据用户名或邮箱查询用户并校验密码与状态，通过后使用Sa-Token创建登录态，返回包含`token`的响应对象。</p>
     *
     * @param request 登录请求（用户名或邮箱、密码）
     * @param clientIp 客户端IP，用于记录审计信息
     * @return 登录响应对象，成功时包含用户基本信息与token
     */
    public LoginResponseDTO login(LoginRequestDTO request, String clientIp) {
        log.info("用户登录尝试: {}", request.getUsernameOrEmail());
        // 1.根据用户名或邮箱查询用户
        User user = userRepository.findByUsernameOrEmail(
                request.getUsernameOrEmail(),
                request.getUsernameOrEmail()
        ).orElse(null);

        if (user == null) {
            log.warn("用户不存在: {}", request.getUsernameOrEmail());
            return LoginResponseDTO.builder()
                    .success(false)
                    .message("用户名或密码错误")
                    .build();
        }
        // 2.检查登录态 0-Active
        if (user.getStatus() != 0) {
            log.warn("用户状态异常: {}, status={}", request.getUsernameOrEmail(), user.getStatus());
            return LoginResponseDTO.builder()
                    .success(false)
                    .message("用户已被禁用或锁定")
                    .build();
        }
        // 3.检验账号密码
        if (!bCryptPasswordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("密码错误: {}", request.getUsernameOrEmail());
            return LoginResponseDTO.builder()
                    .success(false)
                    .message("用户名或密码错误")
                    .build();
        }
        // 4.设置最后一次的登录态
        user.setLastLoginAt(LocalDateTime.now());
        user.setLastLoginIp(clientIp);
        userRepository.save(user);
        // 5.设置权限名字
        List<String> roleNames = userRoleRepository.findRoleNamesByUserId(user.getId());
        // 取第一个
        String roleName = roleNames != null && !roleNames.isEmpty()
            ? roleNames.get(0)
            : "系统管理员";
        log.info("用户登录成功: {}, 角色: {}", user.getUsername(), roleName);
        // 6.使用Sa-Token创建登录态与用户id映射并返回
        StpUtil.login(user.getId());
        String tokenValue = StpUtil.getTokenValue();
        return LoginResponseDTO.builder()
                .success(true)
                .message("登录成功")
                .userId(user.getId())
                .username(user.getUsername())
                .realName(roleName)
                .token(tokenValue)
                .build();

    }

}

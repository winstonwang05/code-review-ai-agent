package com.codeguardian.service.auth;

import cn.dev33.satoken.stp.StpInterface;
import com.codeguardian.entity.UserRole;
import com.codeguardian.repository.RolePermissionRepository;
import com.codeguardian.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @description: Sa-Token权限与角色解析实现
 * <p>基于数据库的用户-角色与角色-权限关系，按登录ID加载当前主体的权限与角色列表。</p>
 * @author: Winston
 * @date: 2026/2/27 17:01
 * @version: 1.0
 */
@Component
@RequiredArgsConstructor
public class SaPermissionImpl implements StpInterface {

    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;


    /**
     * 加载权限列表
     *
     * @param loginId 登录ID（用户ID）
     * @param loginType 登录类型（未使用）
     * @return 权限代码列表
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        // 1.获取用户id
        long userId = Long.parseLong(String.valueOf(loginId));
        // 2.查询用户表获取用户角色
        List<UserRole> userRoles = userRoleRepository.findByUserId(userId);
        // 3.查询角色-权限关联表将权限放入Set集合中去重
        Set<String> permissions = new HashSet<>();
        for  (UserRole userRole : userRoles) {
            permissions.addAll(rolePermissionRepository.findPermissionCodesByRoleId(userRole.getRoleId()));
        }
        return new ArrayList<>(permissions);
    }

    /**
     * 加载角色列表
     *
     * @param loginId 登录ID（用户ID）
     * @param loginType 登录类型（未使用）
     * @return 角色代码列表
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = Long.parseLong(String.valueOf(loginId));
        return userRoleRepository.findRoleCodesByUserId(userId);
    }
}

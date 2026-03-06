package com.codeguardian.service;

import com.codeguardian.dto.RoleCreateDTO;
import com.codeguardian.dto.RoleDTO;
import com.codeguardian.dto.RoleUpdateDTO;
import com.codeguardian.entity.Permission;
import com.codeguardian.entity.Role;
import com.codeguardian.entity.RolePermission;
import com.codeguardian.repository.PermissionRepository;
import com.codeguardian.repository.RolePermissionRepository;
import com.codeguardian.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @description: 角色服务类，与权限相关联
 * @author: Winston
 * @date: 2026/2/28 19:50
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionRepository permissionRepository;

    /**
     * 查询所有角色
     */
    @Transactional(readOnly = true)
    public List<Role> getAllRoles() {
        return roleRepository.findAll();
    }


    /**
     * 查询所有角色（包含权限信息）
     */
    @Transactional(readOnly = true)
    public List<RoleDTO> getAllRolesWithPermissions() {
        List<Role> allRoles = getAllRoles();
        return allRoles.stream().map(
                role -> {
                    List<String> permissions = rolePermissionRepository.findPermissionCodesByRoleId(role.getId());
                    return RoleDTO.builder()
                            .id(role.getId())
                            .code(role.getCode())
                            .name(role.getName())
                            .description(role.getDescription())
                            .status(role.getStatus())
                            .permissions(permissions)
                            .build();
                }
        ).collect(Collectors.toList());
    }
    /**
     * 根据角色id查询角色（带权限信息），由于前端传来的id可能不存在，所以需要提前查询一遍数据库是否存在
     */
    @Transactional(readOnly = true)
    public RoleDTO getRoleById(Long id) {
        Role role = roleRepository
                .findById(id)
                .orElseThrow(() -> new RuntimeException("角色不存在"));
        List<String> permissions = rolePermissionRepository.findPermissionCodesByRoleId(role.getId());
        return RoleDTO.builder()
                .id(role.getId())
                .code(role.getCode())
                .name(role.getName())
                .description(role.getDescription())
                .status(role.getStatus())
                .permissions(permissions)
                .build();
    }

    /**
     * 创建角色
     */
    @Transactional
    public RoleDTO createRole(RoleCreateDTO createDTO) {
        // 1.判断当前角色是否存在
        if (roleRepository.existsByCode(createDTO.getCode())) {
            throw new RuntimeException("角色代码已存在");
        }
        // 2.不存在则创建角色信息并保存到数据库
        Role role = Role.builder()
                .code(createDTO.getCode())
                .name(createDTO.getName())
                .description(createDTO.getDescription())
                .status(createDTO.getStatus() != null ? createDTO.getStatus() : 0)
                .createdAt(LocalDateTime.now())
                .build();

        role = roleRepository.save(role);
        // 3.分配权限
        if (createDTO.getPermissionCodes() != null && !createDTO.getPermissionCodes().isEmpty()) {
            assignPermissions(role.getId(), createDTO.getPermissionCodes());
        }

        log.info("创建角色成功: code={}, id={}", role.getCode(), role.getId());
        return getRoleById(role.getId());
    }


    /**
     * 更新角色
     */
    @Transactional
    public RoleDTO updateRole(Long id, RoleUpdateDTO updateDTO) {
        // 1.判断需要更新的角色是否存在
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("角色不存在"));
        // 2.更新基本信息并保存到数据库

        if (StringUtils.hasText(updateDTO.getName())) {
            role.setName(updateDTO.getName());
        }
        if (updateDTO.getDescription() != null) {
            role.setDescription(updateDTO.getDescription());
        }
        if (updateDTO.getStatus() != null) {
            role.setStatus(updateDTO.getStatus());
        }

        role = roleRepository.save(role);
        // 3.更新分配角色
        if (updateDTO.getPermissionCodes() != null) {
            assignPermissions(id, updateDTO.getPermissionCodes());
        }

        log.info("更新角色成功: id={}", id);
        return getRoleById(id);
    }
    /**
     * 根据角色编码查询角色
     */
    @Transactional(readOnly = true)
    public Role getRoleByCode(String code) {
        return roleRepository.findByCode(code)
                .orElseThrow(() -> new RuntimeException("角色不存在"));
    }


    /**
     * 删除角色 (联合表和Role表)
     */
    @Transactional
    public void deleteRole(Long id) {
        if (!roleRepository.existsById(id)) {
            throw new RuntimeException("角色不存在");
        }

        // 删除角色权限关联
        rolePermissionRepository.deleteByRoleId(id);

        // 删除角色
        roleRepository.deleteById(id);

        log.info("删除角色成功: id={}", id);
    }

    /**
     * 分配权限
     * @param roleId 需要分配权限的角色id
     * @param permissionCodes 需要添加的权限
     */
    @Transactional
    public void assignPermissions(Long roleId, List<String> permissionCodes) {
        // 1.删除原角色id下的所有权限
        rolePermissionRepository.deleteByRoleId(roleId);
        // 2.添加权限到联合表（RolePermission），首先查询需要添加的权限对应Permission表的id
        for  (String permissionCode : permissionCodes) {
            Permission permission = permissionRepository.findByCode(permissionCode)
                    .orElseThrow(() -> new RuntimeException("权限不存在: " + permissionCode));

            RolePermission rolePermission = RolePermission.builder()
                    .roleId(roleId)
                    .permissionId(permission.getId())
                    .createdAt(LocalDateTime.now())
                    .build();
            // 3.保存到联合表中
            rolePermissionRepository.save(rolePermission);
        }
        log.info("分配权限成功: roleId={}, permissions={}", roleId, permissionCodes);
    }

}

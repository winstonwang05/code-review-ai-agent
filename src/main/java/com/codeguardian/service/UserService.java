package com.codeguardian.service;

import com.codeguardian.dto.*;
import com.codeguardian.entity.Role;
import com.codeguardian.entity.User;
import com.codeguardian.entity.UserRole;
import com.codeguardian.repository.RoleRepository;
import com.codeguardian.repository.UserRepository;
import com.codeguardian.repository.UserRoleRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;


/**
 * @description: 用户服务类，包括CRUD
 * @author: Winston
 * @date: 2026/2/27 21:07
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    /**
     * 分页查询用户信息
     */
    @Transactional(readOnly = true)
    public PageResult<UserDTO> queryUsers(UserQueryDTO queryDTO) {
        // 1.构建查询条件
        Specification<User> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // 2.条件一：关键词搜索（用户名或者邮箱）
            if (StringUtils.hasText(queryDTO.getKeyword())) {
                String keyword = "%"  + queryDTO.getKeyword() + "%";
                Predicate usernamePredicate = cb.like(root.get("username"), keyword);
                Predicate emailPredicate = cb.like(root.get("email"), keyword);
                predicates.add(cb.or(usernamePredicate, emailPredicate));
            }
            // 3.条件二：当前活跃状态 0-活跃， 如果请求中有，就会作为条件查询，没有就扔掉
            if (queryDTO.getStatus() != null) {
                Predicate statusPredicate = cb.equal(root.get("status"), queryDTO.getStatus());
                predicates.add(cb.and(statusPredicate));
            }
            // 4.条件三：查询角色信息，连表查询
            if (StringUtils.hasText(queryDTO.getRoleCode())) {
                // 4.1创建返回Long类型（用户id）的子查询
                assert query != null;
                Subquery<Long> subquery = query.subquery(Long.class);
                // 4.2子查询的涉及的两张表 UserRole， Role
                Root<UserRole> userRoleRoot = subquery.from(UserRole.class);
                Root<Role> roleRoot = subquery.from(Role.class);
                // 4.3子查询的内容是UserRole表中的userId
                subquery.select(userRoleRoot.get("userId"));
                // 4.4创建子查询的条件
                subquery.where(
                        cb.equal(userRoleRoot.get("roleId"), roleRoot.get("id")),
                        cb.equal(roleRoot.get("code"), queryDTO.getRoleCode())
                );
                // 4.5主查询条件：主表 User 的 id 必须在这个子查询查出的 userId 集合里 (IN 语句)
                predicates.add(root.get("userId").in(subquery));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        PageRequest pageable = PageRequest.of(
                queryDTO.getPage(),
                queryDTO.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        // 5.查询返回
        Page<User> userPage = userRepository.findAll(specification, pageable);
        List<UserDTO> userDTOs = userPage.getContent().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        return PageResult.<UserDTO>builder()
                .content(userDTOs)
                .totalElements(userPage.getTotalElements())
                .totalPages(userPage.getTotalPages())
                .page(queryDTO.getPage())
                .size(queryDTO.getSize())
                .hasPrevious(userPage.hasPrevious())
                .hasNext(userPage.hasNext())
                .build();
    }

    /**
     * 创建用户
     */
    @Transactional
    public UserDTO createUser(UserCreateDTO createDTO) {
        // 1.检验用户名或者邮箱是否存在
        if (userRepository.existsByUsername(createDTO.getUsername())) {
            throw new RuntimeException("用户名已存在");
        }

        if (userRepository.existsByEmail(createDTO.getEmail())) {
            throw new RuntimeException("邮箱已存在");
        }
        // 2.创建保存到User表中
        User user = User.builder()
                .username(createDTO.getUsername())
                .email(createDTO.getEmail())
                .passwordHash(passwordEncoder.encode(createDTO.getPassword()))
                .realName(createDTO.getRealName())
                .phone(createDTO.getPhone())
                .status(createDTO.getStatus() != null ? createDTO.getStatus() : 0)
                .createdAt(LocalDateTime.now())
                .build();

        userRepository.save(user);
        // 3.分配角色
        if (createDTO.getRoleCodes() != null && !createDTO.getRoleCodes().isEmpty()) {
            assignRoles(user.getId(), createDTO.getRoleCodes());
        }

        log.info("创建用户成功: username={}, id={}", user.getUsername(), user.getId());
        return convertToDTO(user);
    }

    /**
     * 根据ID查询用户
     */
    @Transactional(readOnly = true)
    public UserDTO getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        return convertToDTO(user);
    }


    /**
     * 更新用户
     */
    @Transactional
    public UserDTO updateUser(Long id, UserUpdateDTO updateDTO) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        // 1.更新用户名
        if (StringUtils.hasText(updateDTO.getRealName())) {
            user.setRealName(updateDTO.getRealName());
        }
        // 2.更新邮箱
        if (StringUtils.hasText(updateDTO.getEmail())) {
            if (userRepository.existsByEmail(updateDTO.getEmail()) &&
                    !user.getEmail().equals(updateDTO.getEmail())) {
                throw new RuntimeException("邮箱已被使用");
            }
            user.setEmail(updateDTO.getEmail());
        }
        // 3.更新号码
        if (StringUtils.hasText(updateDTO.getPhone())) {
            user.setPhone(updateDTO.getPhone());
        }
        // 4.更新状态
        if (updateDTO.getStatus() != null) {
            user.setStatus(updateDTO.getStatus());
        }
        // 5.保存
        user = userRepository.save(user);
        // 6.更新角色
        if (updateDTO.getRoleCodes() != null) {
            assignRoles(id, updateDTO.getRoleCodes());
        }
        log.info("更新用户成功: id={}", id);
        return convertToDTO(user);

    }

    /**
     * 分配角色，保存到中间表中
     */
    @Transactional
    public void assignRoles(Long userId, List<String> roleCodes) {
        // 1.先删除原先的
        userRoleRepository.deleteByUserId(userId);
        // 2.新增
        for  (String roleCode : roleCodes) {
            Role role = roleRepository.findByCode(roleCode)
                    .orElseThrow(() -> new RuntimeException("角色不存在: " + roleCode));
            UserRole userRole = UserRole.builder()
                    .userId(userId)
                    .roleId(role.getId())
                    .createdAt(LocalDateTime.now())
                    .build();

            userRoleRepository.save(userRole);
        }
    }

    /**
     * 删除用户 用户表和关联表都删除
     */
    @Transactional
    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw new RuntimeException("用户不存在");
        }

        // 删除用户角色关联
        userRoleRepository.deleteByUserId(id);

        // 删除用户
        userRepository.deleteById(id);

        log.info("删除用户成功: id={}", id);
    }




    /**
     * 转化为UserDTO
     */
    public UserDTO convertToDTO(User user) {

        // 1.查询用户角色信息
        List<String> roleCodes = userRoleRepository.findRoleCodesByUserId(user.getId());
        // 2.构建返回
        return UserDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .realName(user.getRealName())
                .phone(user.getPhone())
                .avatarUrl(user.getAvatarUrl())
                .status(user.getStatus())
                .roles(roleCodes)
                .createdAt(user.getCreatedAt())
                .lastLoginAt(user.getLastLoginAt())
                .lastLoginIp(user.getLastLoginIp())
                .build();

    }

}

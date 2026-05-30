package com.datanote.config;

import com.datanote.model.DnUser;
import com.datanote.service.RbacService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 从 MySQL dn_user 表加载用户的 UserDetailsService。
 * <p>
 * 安全降级：当 dn_user 表不存在 / 查询异常 / 用户不存在或停用时，统一抛
 * {@link UsernameNotFoundException}，由 AuthenticationManager 中的内存兜底
 * provider（配置 admin）接管，绝不向上抛 SQL 异常导致 500、也不会锁死。
 */
@Service
@RequiredArgsConstructor
public class DbUserDetailsService implements org.springframework.security.core.userdetails.UserDetailsService {

    private final RbacService rbacService;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        DnUser user;
        Set<String> perms;
        List<String> roleCodes;
        try {
            user = rbacService.findByUsername(username);
            if (user == null || user.getStatus() == null || user.getStatus() != 1) {
                throw new UsernameNotFoundException("用户不存在或已停用: " + username);
            }
            perms = rbacService.getUserPerms(user.getId());
            roleCodes = rbacService.getUserRoleCodesByUsername(username);
        } catch (UsernameNotFoundException e) {
            throw e;
        } catch (Exception e) {
            // dn_user 表未建 / 查询异常 → 降级为未找到，交由内存兜底 provider
            throw new UsernameNotFoundException("用户数据源不可用: " + username);
        }

        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String code : roleCodes) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + code));
        }
        for (String perm : perms) {
            authorities.add(new SimpleGrantedAuthority(perm));
        }

        return User.withUsername(user.getUsername())
                .password(user.getPassword())
                .authorities(authorities)
                .build();
    }
}

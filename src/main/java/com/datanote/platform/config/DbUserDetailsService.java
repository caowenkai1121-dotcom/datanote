package com.datanote.platform.config;

import com.datanote.platform.iam.model.DnUser;
import com.datanote.platform.iam.RbacService;
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
            // 用户不存在 → UsernameNotFoundException, 由内存兜底 provider 接管(仅供 dn_user 为空时引导)
            if (user == null) {
                throw new UsernameNotFoundException("用户不存在: " + username);
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

        // 安全关键: DB 中存在但被停用 → 返回 disabled 用户。DaoAuthenticationProvider 会抛
        // DisabledException(AccountStatusException), ProviderManager 立即终止、不回落到内存兜底 admin,
        // 从而保证"停用同名账号"真正生效(原来抛 UsernameNotFoundException 会被内存 admin 接管, 停用失效)。
        boolean enabled = user.getStatus() != null && user.getStatus() == 1;
        return User.withUsername(user.getUsername())
                .password(user.getPassword())
                .disabled(!enabled)
                .authorities(authorities)
                .build();
    }
}

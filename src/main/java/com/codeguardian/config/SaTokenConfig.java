package com.codeguardian.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token拦截器配置
 *
 * <p>统一拦截敏感路径进行登录校验：
 * /admin/**、/api/**（排除认证接口）、/review/**。
 * 静态资源与健康检查等公共端点放行。</p>
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /**
     * 注册Sa-Token拦截器并声明路径匹配规则
     *
     * @param registry Spring MVC拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> {
            SaRouter.match("/admin/**").check(r -> StpUtil.checkLogin());
            SaRouter.match("/api/**").notMatch("/api/auth/**").check(r -> StpUtil.checkLogin());
            SaRouter.match("/review/**").check(r -> StpUtil.checkLogin());
        })).addPathPatterns("/**")
          .excludePathPatterns(
                  "/login",
                  "/api/auth/login",
                  "/api/auth/register",
                  "/logout",
                  "/actuator/**",
                  "/error",
                  "/css/**",
                  "/js/**",
                  "/images/**"
          );
    }
}

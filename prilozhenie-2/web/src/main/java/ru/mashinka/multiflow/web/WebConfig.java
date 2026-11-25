package ru.mashinka.multiflow.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import ru.mashinka.multiflow.web.access.AccessInterceptor;
import ru.mashinka.multiflow.web.access.KeyService;
import ru.mashinka.multiflow.web.admin.AdminAuth;
import ru.mashinka.multiflow.web.admin.AdminInterceptor;
import ru.mashinka.multiflow.web.admin.AdminPageInterceptor;

// проверки перед методами: ключ для расчётов, https и пароль для админки
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final KeyService keys;
    private final AdminAuth adminAuth;
    private final ObjectProvider<BuildProperties> build;

    public WebConfig(KeyService keys, AdminAuth adminAuth, ObjectProvider<BuildProperties> build) {
        this.keys = keys;
        this.adminAuth = adminAuth;
        this.build = build;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminPageInterceptor()).addPathPatterns("/admin", "/admin/**");
        registry.addInterceptor(new AdminInterceptor(adminAuth));
        registry.addInterceptor(new AccessInterceptor(keys, StartupCheck.version(build)));
    }

    // страница админки лежит в static/admin/index.html
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/admin", "/admin/");
        registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
    }
}

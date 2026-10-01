package cn.datacraft.config;

import cn.datacraft.cspsim.CspFiles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The container spools up to 500MB to disk; only CSP teacher data imports may use that allowance. */
@Configuration
public class MultipartUploadConfig implements WebMvcConfigurer {
    public MultipartUploadConfig(@org.springframework.beans.factory.annotation.Value("${spring.servlet.multipart.location}") String location) throws java.io.IOException {
        java.nio.file.Files.createDirectories(java.nio.file.Path.of(location).toAbsolutePath().normalize());
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                MultipartHttpServletRequest multipart=org.springframework.web.util.WebUtils.getNativeRequest(request,MultipartHttpServletRequest.class);
                if (multipart!=null) {
                    String path=request.getRequestURI().substring(request.getContextPath().length());
                    boolean data=path.matches("/api/tools/csp-sim/exams/[^/]+/problems/[^/]+/data(?:-files)?");
                    long total=multipart.getMultiFileMap().values().stream().flatMap(java.util.Collection::stream).mapToLong(file->file.getSize()).sum();
                    if (data) CspFiles.dataSize(total);
                    else if (total>25L*1024*1024) throw new IllegalArgumentException("该功能一次上传合计不能超过25MB；题目评测数据支持500MB");
                }
                return true;
            }
        }).addPathPatterns("/api/**");
    }
}

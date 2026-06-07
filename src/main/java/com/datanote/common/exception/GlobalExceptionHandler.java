package com.datanote.common.exception;

import com.datanote.common.model.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器 — 统一异常转换为 R 格式返回
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 资源不存在异常
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public R<?> handleNotFound(ResourceNotFoundException e) {
        log.warn("资源不存在: {}", e.getMessage());
        return R.fail(R.CODE_NOT_FOUND, e.getMessage());
    }

    /**
     * 业务异常：直接返回 message 给前端
     */
    @ExceptionHandler(BusinessException.class)
    public R<?> handleBusiness(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return R.fail(e.getMessage());
    }

    /**
     * 请求参数异常（缺参/类型不匹配/请求体格式错/校验失败）：属客户端输入错误，
     * 返回明确的参数提示而非笼统"系统异常"，并以 WARN 记录（避免对客户端小错打 ERROR 噪音）。
     * 仅回显参数名（API 自身定义，非用户数据），不泄露内部结构。
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            BindException.class
    })
    public R<?> handleBadRequest(Exception e) {
        String msg = "请求参数有误";
        if (e instanceof MissingServletRequestParameterException) {
            msg = "缺少必需参数：" + ((MissingServletRequestParameterException) e).getParameterName();
        } else if (e instanceof MethodArgumentTypeMismatchException) {
            msg = "参数类型错误：" + ((MethodArgumentTypeMismatchException) e).getName();
        } else if (e instanceof HttpMessageNotReadableException) {
            msg = "请求体格式错误";
        }
        log.warn("请求参数异常: {}", e.getMessage());
        return R.fail(msg);
    }

    /**
     * 非预期异常：记录完整堆栈，返回友好提示
     */
    @ExceptionHandler(Exception.class)
    public R<?> handleUnexpected(Exception e) {
        log.error("系统异常", e);
        // 未预期异常(NPE/SQLException等)不回显原始message,避免泄露SQL/路径/内部结构;完整堆栈已记日志供排查
        return R.fail("系统异常，请稍后重试或联系管理员");
    }
}

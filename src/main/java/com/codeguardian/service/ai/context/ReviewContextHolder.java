package com.codeguardian.service.ai.context;

import com.codeguardian.entity.Finding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @description: 审查上下文持有者
 * 用于在线程中传递工具发现的问题
 * @author: Winston
 * @date: 2026/3/8 9:48
 * @version: 1.0
 */
public class ReviewContextHolder {

    private static final ThreadLocal<List<Finding>> findingsHolder = ThreadLocal.withInitial(ArrayList::new);

    /**
     * 添加批量结果
     */
    public static void addFindings(List<Finding> findings) {
        if (findings != null) {
            findingsHolder.get().addAll(findings);
        }
    }

    /**
     * 添加一个
     */
    public static void addFinding(Finding finding) {
        if (finding != null) {
            findingsHolder.get().add(finding);
        }
    }

    /**
     * 获取结果，拷贝一份并设置仅读
     */
    public static List<Finding> getFindings() {
        return Collections.unmodifiableList(new ArrayList<>(findingsHolder.get()));
    }

    /**
     * 清除绑定关系
     */
    public static void clear() {
        findingsHolder.remove();
    }

}

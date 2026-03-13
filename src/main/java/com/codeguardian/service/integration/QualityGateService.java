package com.codeguardian.service.integration;

import com.codeguardian.entity.Finding;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.repository.FindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @description: 质量门禁服务，判断审查结果是否能够合并
 * 判断是否能够 MR/PR
 * @author: Winston
 * @date: 2026/3/12 16:50
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityGateService {


    private final FindingRepository findingRepository;

    /**
     * 检查是否通过质量门禁
     * @param taskId 所审核的任务id
     * @param blockOn 所设置的要求，一旦出现这个要求，无法通过门禁
     * @return 返回布尔
     */
    public boolean checkQualityGate(Long taskId, String blockOn) {
        if (taskId == null) {
            return true;
        }

        List<Finding> findings = findingRepository.findByTaskId(taskId);
        return checkQualityGate(findings, blockOn, taskId);
    }

    boolean checkQualityGate(List<Finding> findings, String blockOn) {
        return checkQualityGate(findings, blockOn, null);
    }


    private boolean checkQualityGate(List<Finding> findings, String blockOn, Long taskId) {
        // 1.查询结果并分组，key：严重程度 value：数量
        Map<Integer, Long> counts = findings.stream()
                .filter(finding -> finding.getSeverity() != null)
                .collect(Collectors.groupingBy(Finding::getSeverity, Collectors.counting()));

        // 2.获取每种严重程度次数
        long critical = counts.getOrDefault(SeverityEnum.CRITICAL.getValue(), 0L);
        long high = counts.getOrDefault(SeverityEnum.HIGH.getValue(), 0L);
        long medium = counts.getOrDefault(SeverityEnum.MEDIUM.getValue(), 0L);
        long low = counts.getOrDefault(SeverityEnum.LOW.getValue(), 0L);



        if (blockOn == null || blockOn.isBlank()) {
            return true; // 默认不阻断
        }

        // 3.与设置的严重要求判断
        switch (blockOn.toUpperCase()) {
            case "LOW":
                if (low > 0) return false;
                // fallthrough
            case "MEDIUM":
                if (medium > 0) return false;
                // fallthrough
            case "HIGH":
                if (high > 0) return false;
                // fallthrough
            case "CRITICAL":
                if (critical > 0) return false;
                break;
            default:
                log.warn("未知的阻断级别: {}, 忽略阻断策略", blockOn);
                return true;
        }
        return true;
    }
}

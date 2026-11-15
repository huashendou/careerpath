package com.careerpath.interview;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 面试记录与复盘（模块 8 简化版）。 */
public final class InterviewDtos {

    private InterviewDtos() {
    }

    /** 一道题 + 自评 1–5 + 备注。前端按「问题 / 我答得怎么样」两列录入。 */
    public record Question(String question, Integer selfRating, String note) {
    }

    public record View(long id, Long applicationId, Long jobPositionId, String companyName, String jobTitle,
                       LocalDate interviewDate, String round, String format, List<Question> questions,
                       String reflection, String result, List<String> relatedSkills,
                       Instant createdAt, Instant updatedAt) {
    }

    public record Create(Long applicationId, Long jobPositionId, String companyName, String jobTitle,
                         LocalDate interviewDate, String round, String format, List<Question> questions,
                         String reflection, String result, List<String> relatedSkills) {
    }

    public record Patch(String companyName, String jobTitle, LocalDate interviewDate, String round,
                        String format, List<Question> questions, String reflection, String result,
                        List<String> relatedSkills) {
    }
}

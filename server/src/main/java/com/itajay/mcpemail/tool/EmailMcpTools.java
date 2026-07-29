package com.itajay.mcpemail.tool;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;


/*
自动配置将自动检测并注册来自以下来源的所有工具回调：
    单个 ToolCallback bean    ToolCallback bean 列表  ToolCallbackProvider bean
 */
@Component
public class EmailMcpTools {

    private static final Logger log = LoggerFactory.getLogger(EmailMcpTools.class);
    private final JavaMailSender mailSender;
    private final String senderAddress;

    public EmailMcpTools(JavaMailSender mailSender,
                         @Value("${spring.mail.username}") String senderAddress) {
        this.mailSender = mailSender;
        this.senderAddress = senderAddress;
    }

    @Tool(description = "Send an email via SMTP. Supports plain text and HTML, multiple recipients (comma-separated), optional CC.")
    public String sendEmail(
            @ToolParam(description = "Recipient email(s), comma-separated") String to,
            @ToolParam(description = "Email subject") String subject,
            @ToolParam(description = "Email body (plain text or HTML)") String body,
            @ToolParam(description = "Whether body is HTML") boolean isHtml,
            @ToolParam(description = "CC recipient(s), optional") String cc) {
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
            h.setFrom(senderAddress);
            h.setTo(to.split(","));
            h.setSubject(subject);
            h.setText(body, isHtml);
            if (cc != null && !cc.isBlank()) h.setCc(cc.split(","));
            mailSender.send(msg);
            log.info("Email sent to {}", to);
            return "Email sent to " + to + (cc != null && !cc.isBlank() ? " (CC: " + cc + ")" : "");
        } catch (Exception e) {
            log.error("sendEmail failed", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Send the same email to multiple recipients individually (batch mode).")
    public String sendEmailBatch(
            @ToolParam(description = "JSON array string: [\"a@x.com\",\"b@x.com\"]") String recipients,
            @ToolParam(description = "Email subject") String subject,
            @ToolParam(description = "Email body") String body,
            @ToolParam(description = "Whether body is HTML") boolean isHtml) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var list = mapper.readTree(recipients);
            int ok = 0;
            StringBuilder errs = new StringBuilder();
            for (var r : list) {
                try {
                    MimeMessage msg = mailSender.createMimeMessage();
                    MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
                    h.setFrom(senderAddress);
                    h.setTo(r.asText().trim());
                    h.setSubject(subject);
                    h.setText(body, isHtml);
                    mailSender.send(msg);
                    ok++;
                } catch (Exception e) {
                    errs.append(r.asText()).append(":").append(e.getMessage()).append("; ");
                }
            }
            String result = "Batch done. Success: " + ok + "/" + list.size();
            if (!errs.isEmpty()) result += ". Errors: " + errs;
            return result;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
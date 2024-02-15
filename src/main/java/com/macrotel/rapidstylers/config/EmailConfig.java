package com.macrotel.rapidstylers.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import javax.mail.internet.MimeMessage;

@Component
public class EmailConfig {
    @Autowired
    private JavaMailSender javaMailSender;

    @Value("${spring.mail.username}") private String sender;
    public String sendSimpleMail(String receiverEmail, String subject, String body) {
        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            SimpleMailMessage mailMessage = new SimpleMailMessage();
            helper.setFrom(sender);
            helper.setTo(receiverEmail);
            helper.setSubject(subject);
            helper.setText(body, true);  // Set the second parameter to true for HTML content

            javaMailSender.send(message);
//            System.out.println("working");
            return "Mail Sent Successfully...";


        } catch (Exception e) {
//            System.out.println("not working");
//            System.out.println(e);
            return "Error while Sending Mail";
        }
    }
}
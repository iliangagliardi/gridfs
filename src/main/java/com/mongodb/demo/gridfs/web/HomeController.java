package com.mongodb.demo.gridfs.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the single Thymeleaf page. Everything the UI does afterwards is a
 * {@code fetch()} against {@code /api/**}, so this is the only view mapping in
 * the whole application — kept apart from the {@code @RestController}s so that
 * {@link ApiExceptionHandler}'s JSON error shape never applies to it.
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String index() {
        return "index";
    }
}

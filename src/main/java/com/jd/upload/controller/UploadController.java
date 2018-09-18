package com.jd.upload.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class UploadController {

    @RequestMapping(value = "/upload")
    public String upload(){
        return "upload";
    }
}

package com.traiana.nagger

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

object Nagger extends App {
  SpringApplication.run(classOf[Nagger], args: _*)
}

@SpringBootApplication
class Nagger() {}

package com.rabbitmq.perf;

public interface StartListener {

  StartListener NO_OP = id -> { };

  void started(int id);

}

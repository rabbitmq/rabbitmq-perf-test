package com.rabbitmq.perf;

interface InstanceSynchronization {

  InstanceSynchronization NO_OP = () -> {
  };

  void synchronize() throws Exception;

  default void addPostSyncListener(Runnable listener) {
    listener.run();
  }

}

package com.example.eventplatform.event.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@AllArgsConstructor
public class QueueStruct {

  private long identifier;
  private long rank;
  private String entryToken;
}

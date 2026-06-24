package com.example.eventplatform.event.mapper;

import com.example.eventplatform.event.dto.ResponseEvent.ResponseCreateEvent;
import com.example.eventplatform.event.dto.ResponseEvent.ResponseEventDetail;
import com.example.eventplatform.event.dto.ResponseEvent.ResponseEventStock;
import com.example.eventplatform.event.entity.Event;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface EventMapper {

  EventMapper INSTANCE = Mappers.getMapper(EventMapper.class);

  @Mappings({
      @Mapping(target = "eventId", source = "id"),
      @Mapping(target = "totalStock", source = "total_stock"),
      @Mapping(target = "remainingStock", source = "remaining_stock"),
      @Mapping(target = "openAt", source = "open_at"),
      @Mapping(target = "closeAt", source = "close_at")
  })
  public ResponseEventDetail eventToResponseEventDetail(Event event);

  @Mapping(target = "eventId", source = "id")
  public ResponseCreateEvent eventToResponseCreateEvent(Event event);

  @Mappings({
      @Mapping(target = "eventId", source = "id"),
      @Mapping(target = "remainingStock", source = "remaining_stock")
  })
  public ResponseEventStock eventToResponseEventStock(Event event, boolean soldOut);
}

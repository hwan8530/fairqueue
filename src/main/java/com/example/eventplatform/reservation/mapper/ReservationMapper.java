package com.example.eventplatform.reservation.mapper;

import com.example.eventplatform.reservation.dto.ResponseReservation.DeleteReservationDTO;
import com.example.eventplatform.reservation.dto.ResponseReservation.ReservationDTO;
import com.example.eventplatform.reservation.entity.Reservation;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ReservationMapper {

  @Mappings({
      @Mapping(target = "reservationId", source = "id"),
      @Mapping(target = "issuedCode", source = "issued_code"),
      @Mapping(target = "expiresAt", source = "expires_at")
  })
  public ReservationDTO toResponse(Reservation reservation);

  @Mapping(target = "reservationId", source = "id")
  public DeleteReservationDTO toDeleteResponse(Reservation reservation);
}

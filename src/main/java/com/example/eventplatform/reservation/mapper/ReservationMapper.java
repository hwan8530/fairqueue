package com.example.eventplatform.reservation.mapper;

import com.example.eventplatform.reservation.dto.ResponseReservation.deleteReservationDTO;
import com.example.eventplatform.reservation.dto.ResponseReservation.reservationDTO;
import com.example.eventplatform.reservation.entity.Reservation;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ReservationMapper {

  @Mappings({
      @Mapping(target = "reservationId", source = "id"),
      @Mapping(target = "status", source = "status.status"),
      @Mapping(target = "issuedCode", source = "issued_code"),
      @Mapping(target = "expiresAt", source = "expires_at")
  })
  public reservationDTO toResponse(Reservation reservation);

  @Mappings({
      @Mapping(target = "reservationId", source = "id"),
      @Mapping(target = "status", source = "status.status")
  })
  public deleteReservationDTO toDeleteResponse(Reservation reservation);
}

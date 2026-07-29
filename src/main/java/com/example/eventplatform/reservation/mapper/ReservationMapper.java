package com.example.eventplatform.reservation.mapper;

import com.example.eventplatform.reservation.dto.ResponseReservation.DeleteReservationDTO;
import com.example.eventplatform.reservation.dto.ResponseReservation.ReservationDTO;
import com.example.eventplatform.reservation.entity.Reservation;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.ReportingPolicy;

/*
 * unmappedTargetPolicy = IGNORE라서 target 프로퍼티를 빠뜨려도 빌드가 조용히 통과한다.
 * MapStruct의 기본 매칭은 "created_at" 같은 snake_case source를 "createdAt" camelCase
 * target에 자동으로 대응시켜주지 않는데(둘 다 명시하지 않으면 그냥 무시됨), createdAt/
 * confirmedAt이 여기 빠져 있어서 생성된 구현체가 이 두 필드를 아예 채우지 않고 있었다 -
 * 모든 예약 응답에서 createdAt/confirmedAt이 항상 null이었다(명세서 7.4 응답 예시와 다름).
 * 통합 테스트로 confirmedAt을 실제로 검증하다가 발견했다
 * (docs/refactoring-and-abstraction-review.md 참고).
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ReservationMapper {

  @Mappings({
      @Mapping(target = "reservationId", source = "id"),
      @Mapping(target = "issuedCode", source = "issued_code"),
      @Mapping(target = "expiresAt", source = "expires_at"),
      @Mapping(target = "createdAt", source = "created_at"),
      @Mapping(target = "confirmedAt", source = "confirmed_at")
  })
  public ReservationDTO toResponse(Reservation reservation);

  @Mapping(target = "reservationId", source = "id")
  public DeleteReservationDTO toDeleteResponse(Reservation reservation);
}

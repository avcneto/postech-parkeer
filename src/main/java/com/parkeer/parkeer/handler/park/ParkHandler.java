package com.parkeer.parkeer.handler.park;

import com.parkeer.parkeer.dto.park.ParkDTO;
import com.parkeer.parkeer.dto.park.UnparkDTO;
import com.parkeer.parkeer.exception.BadRequestException;
import com.parkeer.parkeer.service.park.ParkService;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static java.lang.String.format;

@Component
public record ParkHandler(
        ParkService parkService
) {

    private static final String PARK_PATH_ID = "/park/%s";
    private static final String BODY_IS_EMPTY = "body is empty";

    public Mono<ServerResponse> park(final ServerRequest request) {
        return request
                .bodyToMono(ParkDTO.class)
                .switchIfEmpty(Mono.error(new BadRequestException(BODY_IS_EMPTY)))
                .flatMap(parkService::park)
                .flatMap(park -> ServerResponse.created(URI.create(format(PARK_PATH_ID, park.getPlate()))).bodyValue(park));
    }

    public Mono<ServerResponse> unPark(final ServerRequest request) {
        return request
                .bodyToMono(UnparkDTO.class)
                .switchIfEmpty(Mono.error(new BadRequestException(BODY_IS_EMPTY)))
                .flatMapMany(parkService::unPark)
                .collectList()
                .flatMap(ParkHandler::getServerResponseEmptyOrNot);
    }

    public Mono<ServerResponse> getParkByPlateAndStatus(final ServerRequest request) {
        return parkService.getParkByPlateAndStatus(request.queryParams())
                .collectList()
                .flatMap(ParkHandler::getServerResponseEmptyOrNot);
    }

    private static <T> Mono<ServerResponse> getServerResponseEmptyOrNot(List<T> parks) {
        if (parks.isEmpty()) {
            return ServerResponse.ok().bodyValue(Collections.emptyList());
        }

        return ServerResponse.ok().bodyValue(parks);
    }
}

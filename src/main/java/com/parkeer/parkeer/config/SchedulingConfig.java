package com.parkeer.parkeer.config;

import com.parkeer.parkeer.entity.park.Park;
import com.parkeer.parkeer.entity.park.ParkRedis;
import com.parkeer.parkeer.entity.park.Status;
import com.parkeer.parkeer.entity.receipt.Receipt;
import com.parkeer.parkeer.repository.park.ParkRedisRepositoryImpl;
import com.parkeer.parkeer.repository.park.ParkRepository;
import com.parkeer.parkeer.repository.receipt.ReceiptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

import static com.parkeer.parkeer.util.Producer.producerMessageConsole;
import static com.parkeer.parkeer.util.Validators.getDuration;
import static com.parkeer.parkeer.util.Validators.getPriceTotalByMinute;
import static com.parkeer.parkeer.util.Validators.hasTimeExpired;
import static com.parkeer.parkeer.util.Validators.isNearTimeExpired;
import static java.lang.String.format;

@Slf4j
@Configuration
@EnableScheduling
public class SchedulingConfig {

    public static final BigDecimal PRICE_POR_MINUTE_PARKED = new BigDecimal("0.16");
    private static final String REDIS_SYNCHRONIZATION_EMPTY_MESSAGE = "redis empty, synchronization not performed.";
    private static final String SUCCESSFUL_SYNCHRONIZATION = "successful synchronization";
    private static final String SUCCESSFUL_OPERATION_FOR_PARK_REDIS = "successful operation for ParkRedis";
    private static final String SUCCESSFUL_OPERATION_FOR_PARK = "successful operation for Park";
    private static final String ERROR_DURING_SYNCHRONIZATION = "error during synchronization: %s";
    private static final String TOPIC_MESSAGE = "TOPIC_PUBLISH: %s";
    private static final String TOPIC_EXPIRED_MESSAGE = "You have 5 minutes left before your time to expired, park: %s ";
    private static final Integer ONE = 1;
    private static final long SYNCHRONIZATION_DATABASE_TIME = 50000;
    private static final long SYNCHRONIZATION_STATUS_TIME = 10000;
    private static final long WARNING_MESSAGE_TIME = 500000;

    private final ReceiptRepository receiptRepository;
    private final ParkRedisRepositoryImpl parkRedisRepository;
    private final ParkRepository parkRepository;

    public SchedulingConfig(ReceiptRepository receiptRepository, ParkRedisRepositoryImpl parkRedisRepository, ParkRepository parkRepository) {
        this.receiptRepository = receiptRepository;
        this.parkRedisRepository = parkRedisRepository;
        this.parkRepository = parkRepository;
    }

    @Scheduled(fixedDelay = WARNING_MESSAGE_TIME)
    private void warningMessage() {
        parkRedisRepository.findByStatus(Status.START)
                .flatMap(parkRedis -> {
                    var now = LocalDateTime.now();
                    if (isNearTimeExpired(now, LocalDateTime.parse(parkRedis.getLastUpdate()))) {
                        producerMessageConsole(parkRedis, TOPIC_EXPIRED_MESSAGE);
                    }

                    return Mono.empty();
                }).subscribe();

        parkRepository.findByStatus(Status.START)
                .flatMap(parkMysql -> {
                    var now = LocalDateTime.now();
                    if (isNearTimeExpired(now, parkMysql.getLastUpdate())) {
                        producerMessageConsole(parkMysql, TOPIC_EXPIRED_MESSAGE);
                    }

                    return Mono.empty();
                }).subscribe();
    }


    @Scheduled(fixedDelay = SYNCHRONIZATION_STATUS_TIME)
    private void synchronizeStatus() {
        try {
            var now = LocalDateTime.now();
            updateParkStatusByRedis(now);
            updateParkStatusByMysql(now);
        } catch (Exception ex) {
            log.error(format(ERROR_DURING_SYNCHRONIZATION, ex.getMessage()));
        }
    }

    private void updateParkStatusByRedis(LocalDateTime now) {
        parkRedisRepository.findByStatus(Status.START)
                .flatMap(parkRedis -> {

                    if (hasTimeExpired(now, LocalDateTime.parse(parkRedis.getLastUpdate()))) {
                        parkRedis.setStatus(Status.END);

                        return parkRepository.save(new Park(parkRedis))
                                .flatMap(savedPark -> parkRedisRepository
                                        .delete(savedPark.getPlate())
                                        .flatMap(it -> {
                                            var duration = getDuration(savedPark.getCreationDate(), savedPark.getLastUpdate());
                                            var receipt = new Receipt(
                                                    savedPark.getUserId(),
                                                    savedPark.getPlate(),
                                                    savedPark.getTime(),
                                                    savedPark.getCreationDate(),
                                                    savedPark.getLastUpdate(),
                                                    duration,
                                                    PRICE_POR_MINUTE_PARKED,
                                                    getPriceTotalByMinute(duration)
                                            );

                                            producerMessageConsole(receipt, TOPIC_MESSAGE);

                                            return receiptRepository.save(receipt);
                                        })
                                        .thenReturn(savedPark))
                                .map(ParkRedis::new);
                    }

                    return Mono.empty();

                })
                .doOnTerminate(() -> log.info(SUCCESSFUL_OPERATION_FOR_PARK_REDIS))
                .subscribe();
    }


    private void updateParkStatusByMysql(LocalDateTime now) {
        parkRepository.findByStatus(Status.START)
                .flatMap(park -> {
                    if (hasTimeExpired(now, park.getLastUpdate())) {
                        park.setVersion(park.getVersion() + ONE);
                        park.setStatus(Status.END);

                        return parkRepository.save(park)
                                .flatMap(parkSaved -> {
                                    var duration = getDuration(parkSaved.getCreationDate(), parkSaved.getLastUpdate());
                                    var receipt = new Receipt(
                                            parkSaved.getUserId(),
                                            parkSaved.getPlate(),
                                            parkSaved.getTime(),
                                            parkSaved.getCreationDate(),
                                            parkSaved.getLastUpdate(),
                                            duration,
                                            PRICE_POR_MINUTE_PARKED,
                                            getPriceTotalByMinute(duration)
                                    );

                                    producerMessageConsole(receipt, TOPIC_MESSAGE);

                                    return receiptRepository.save(receipt);
                                });
                    }

                    return Mono.empty();

                })
                .doOnTerminate(() -> log.info(SUCCESSFUL_OPERATION_FOR_PARK))
                .subscribe();
    }

    @Scheduled(fixedDelay = SYNCHRONIZATION_DATABASE_TIME)
    private void synchronizeDatabase() {
        parkRedisRepository.findAll()
                .collectList()
                .flatMapMany(allRedisPark -> {

                    if (allRedisPark.isEmpty()) {
                        log.info(REDIS_SYNCHRONIZATION_EMPTY_MESSAGE);

                        return Mono.empty();
                    } else {
                        var saveAll = allRedisPark.stream().map(Park::new).toList();
                        var deleteAll = Flux.fromIterable(allRedisPark.stream().map(ParkRedis::getPlate).toList());

                        return parkRepository.saveAll(saveAll)
                                .thenMany(parkRedisRepository.deleteAll(deleteAll))
                                .doOnNext(it -> log.info(SUCCESSFUL_SYNCHRONIZATION))
                                .thenMany(Mono.empty());
                    }
                }).subscribe();
    }
}

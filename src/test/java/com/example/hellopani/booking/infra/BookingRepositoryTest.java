package com.example.hellopani.booking.infra;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.hellopani.booking.domain.Booking;
import com.example.hellopani.booking.domain.BookingStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(BookingRepository.class)
@DisplayName("BookingRepository — 예약 영속화와 상태 전이")
class BookingRepositoryTest {

    @Autowired
    BookingRepository bookingRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    String checkoutId;

    @BeforeEach
    void seedCheckout() {
        checkoutId = "ck-bk-" + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO checkout "
                        + "(checkout_id, user_id, product_id, quoted_price, available_point_snapshot, status, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                checkoutId, "test-user-1", 1L, 150000L, 50000L, "ISSUED",
                LocalDateTime.now().plusMinutes(10));
    }

    @Test
    @DisplayName("insertPending은 Booking을 PENDING_PAYMENT 상태로 영속화하고 자동 생성된 bookingId를 반환한다")
    void insertsPendingBookingAndReturnsGeneratedId() {
        long bookingId = bookingRepository.insertPending(checkoutId, "test-user-1", 1L, 150000L);

        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(booking.checkoutId()).isEqualTo(checkoutId);
        assertThat(booking.userId()).isEqualTo("test-user-1");
        assertThat(booking.productId()).isEqualTo(1L);
        assertThat(booking.status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(booking.totalAmount()).isEqualTo(150000L);
        assertThat(booking.createdAt()).isNotNull();
        assertThat(booking.confirmedAt()).isNull();
    }

    @Test
    @DisplayName("markConfirmed는 status를 CONFIRMED로 바꾸고 confirmed_at을 기록한다")
    void marksConfirmedRecordsTimestamp() {
        long bookingId = bookingRepository.insertPending(checkoutId, "test-user-1", 1L, 150000L);

        LocalDateTime now = LocalDateTime.now();
        int affected = bookingRepository.markConfirmed(bookingId, now);

        assertThat(affected).isEqualTo(1);
        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.confirmedAt()).isNotNull();
    }

    @Test
    @DisplayName("markFailed는 status를 FAILED로 바꾸고 confirmed_at은 그대로 둔다 (감사 기록)")
    void marksFailed() {
        long bookingId = bookingRepository.insertPending(checkoutId, "test-user-1", 1L, 150000L);

        int affected = bookingRepository.markFailed(bookingId);

        assertThat(affected).isEqualTo(1);
        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(booking.status()).isEqualTo(BookingStatus.FAILED);
        assertThat(booking.confirmedAt()).isNull();
    }

    @Test
    @DisplayName("checkout_id UNIQUE 제약: 같은 checkoutId로 두 번 insert하면 DuplicateKeyException을 던진다")
    void rejectsDuplicateCheckoutId() {
        bookingRepository.insertPending(checkoutId, "test-user-1", 1L, 150000L);

        assertThatThrownBy(() -> bookingRepository.insertPending(
                checkoutId, "test-user-1", 1L, 150000L))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("findByCheckoutId로 멱등 응답 재생용 조회를 지원한다")
    void findsByCheckoutId() {
        long bookingId = bookingRepository.insertPending(checkoutId, "test-user-1", 1L, 150000L);

        Booking booking = bookingRepository.findByCheckoutId(checkoutId).orElseThrow();
        assertThat(booking.bookingId()).isEqualTo(bookingId);
    }

    @Test
    @DisplayName("존재하지 않는 bookingId 조회는 빈 Optional을 반환한다")
    void returnsEmptyForUnknownBookingId() {
        assertThat(bookingRepository.findById(999_999L)).isEmpty();
    }
}

package com.example.bookingservice.exception

class InvalidEntryTokenException : RuntimeException("유효하지 않은 입장 토큰입니다")
class SeatAlreadyTakenException : RuntimeException("이미 선택된 좌석입니다")
class BookingNotFoundException : RuntimeException("예약을 찾을 수 없습니다")
class BookingAlreadyCancelledException : RuntimeException("이미 취소된 예약입니다")

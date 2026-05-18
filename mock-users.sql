-- mock users (password: "password", BCrypt 10 rounds)
INSERT INTO users (id, name, password)
SELECT
    'user' || LPAD(gs::text, 3, '0'),
    (ARRAY[
        '김민준', '이서연', '박도윤', '최지우', '정예준',
        '강서현', '조현우', '윤지민', '장수호', '임나연',
        '한지호', '오세아', '서유진', '권태양', '남하늘',
        '백지수', '문성민', '류하린', '신동현', '전수빈'
    ])[((gs - 1) % 20) + 1],
    '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG'
FROM generate_series(1, 100) AS gs;

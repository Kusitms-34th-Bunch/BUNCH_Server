# 사진함 업로드 클라이언트 계약

사진함 업로드 HTTP API의 경로·요청·응답·오류를 정의한다. 업로드 저장 규칙(한도, 같은 사진 합치기,
처리 순서)은 [사진함 업로드 계약](photo-box.md)을 따른다. 사진 목록·상세·날짜 변경·삭제 API는 이 계약에
포함하지 않는다.

값이 확정되지 않은 항목은 "확정 대기"로 표시했고 기본안으로 구현되어 있다.

## 경로와 인증

```text
POST /api/v1/bunches/{bunchId}/photos
```

- 업로드 대상은 경로의 번치로 고정한다(기능명세서 3.2.1). 업로드 도중 현재 번치를 바꿔도 요청한 번치에 저장한다.
- 앨범에서 고른 사진(3.2.2)과 카메라로 찍은 사진(3.2.3)은 같은 API를 쓴다.
- 요청은 `Authorization: Bearer <access-jwt>`가 필요하다. 가입을 완료한 번치 멤버만 올릴 수 있다.
- 응답은 공통 envelope(`success`, `data`, `error`)를 쓰며 정상 응답은 `200 OK`다. [번들 계약](bundle-api.md)의 공통 규칙과 같다.

## 요청

`Content-Type: multipart/form-data`. **요청 1건에 사진 1장**이다(3.2.2 로직 4).

| 필드 | 형식 | 필수 | 설명 |
| --- | --- | --- | --- |
| `photo` | 파일 | O | 앱이 긴 변 2560px로 줄이고(앱 확인) 위치 정보를 지운 JPEG(형식은 확정 대기). `Content-Type`은 `image/jpeg` |
| `uploadBatchId` | UUID | O | 사진 선택창에서 한 번 고른 사진들에 같은 값. 「다시 시도」로 다시 올려도 원래 값을 유지한다(3.2.2 로직 3 ⑤) |
| `capturedDate` | `yyyy-MM-dd` | 선택 | 앱이 사진 파일 정보에서 뽑은 촬영 현지 날짜. 카메라 촬영은 촬영 시각의 날짜(3.2.3) |
| `capturedTime` | `HH:mm:ss` | 선택 | 촬영 현지 시각. **`capturedDate` 없이 보낼 수 없다** |
| `capturedUtcOffset` | `±HH:mm` | 선택 | 촬영한 곳의 UTC 오프셋(예: `+09:00`, 범위 `-12:00`~`+14:00`). 앱이 EXIF `OffsetTimeOriginal`에서 읽고, 앱 안에서 직접 찍은 사진은 기기의 현재 오프셋을 보낸다. 없으면 추천에서 `Asia/Seoul`로 본다 |
| `sourceType` | `CAMERA` / `SCREENSHOT` / `SAVED_IMAGE` / `UNKNOWN` | 선택 | 원본 유형. 앱이 판단한다. `CAMERA`는 EXIF에 `Make`/`Model` 또는 `DateTimeOriginal`이 있거나 앱 안에서 직접 찍은 사진, `SAVED_IMAGE`는 카메라 정보가 없는 저장 이미지, `SCREENSHOT`은 스크린샷으로 판별될 때만, `UNKNOWN`은 앱이 판단하지 못한 경우 |

- 촬영 정보가 없어도 업로드는 성공한다(3.2.2 로직 3 ④). 날짜가 없으면 사진은 「촬영 날짜를 확인할 사진」에 모인다.
- `capturedUtcOffset`과 `sourceType`은 추천 번들 조합이 쓴다(추천 번들 조합 명세 3-6, 4-4, 5-2). 보내지 않은 구버전 앱의 업로드도 성공하고, 값은 비어 있다. 빈 문자열은 보내지 않은 것으로 본다.
- 서버는 `sourceType`을 명세의 값으로 바꿔 저장한다: `CAMERA`→`CAMERA`, `SCREENSHOT`→`SCREENSHOT`, `SAVED_IMAGE`→`SAVED`. `UNKNOWN`은 명세에 없는 값이라 저장하지 않는다(판단 안 됨과 같다). 이 값이 없는 사진을 추천에서 어떻게 볼지는 서버가 추천 입력을 만들 때 정한다.
- 이미 올라온 사진을 다시 올리거나(`ALREADY_UPLOADED`) 다른 멤버가 같은 사진을 올리면(`MERGED`), 기존 사진에 비어 있는 오프셋·원본 유형만 새 값으로 채운다. **이미 값이 있으면 바꾸지 않는다.**
- 위치 정보(EXIF·XMP의 GPS 등)는 앱이 보내기 전에 지운다. 남아 있으면 서버가 거부한다.
- 서버는 리사이즈를 하지 않는다. 긴 변 4096px 초과와 30MiB 초과 파일은 거부한다.
- 서버는 JPEG만 받는다(확정 대기: 앱이 보낼 형식).

```http
POST /api/v1/bunches/42/photos
Authorization: Bearer <access-jwt>
Content-Type: multipart/form-data; boundary=...

--...
Content-Disposition: form-data; name="photo"; filename="a.jpg"
Content-Type: image/jpeg
...
--...
Content-Disposition: form-data; name="uploadBatchId"

0b5f2c1e-6e2d-4a6c-9a7e-6a1d3f0c9b11
--...
Content-Disposition: form-data; name="capturedDate"

2026-10-03
--...
Content-Disposition: form-data; name="capturedTime"

14:05:09
--...
Content-Disposition: form-data; name="capturedUtcOffset"

+09:00
--...
Content-Disposition: form-data; name="sourceType"

CAMERA
--...--
```

## 응답

```json
{
  "success": true,
  "data": {
    "photoId": 1201,
    "result": "STORED"
  },
  "error": null
}
```

| `result` | 의미 | 사진함 변화 |
| --- | --- | --- |
| `STORED` | 새 사진이 저장되었다. 저장 즉시 `READY`다 | 사진 1장 추가 |
| `MERGED` | 다른 멤버가 이미 올린 같은 사진이다. 올린 사람에 내가 추가되었다 | 사진 수 변화 없음 |
| `ALREADY_UPLOADED` | 내가 이미 올린 같은 사진이다 | 변화 없음 |

- 세 경우 모두 `photoId`를 돌려준다. 갤러리에서 가져오기(4.3.7)는 이 `photoId`로 번들에 사진을 넣는다.
- 같은 사진은 **파일이 완전히 같은 사진**이다. 서버가 업로드된 파일의 SHA-256으로 판단하므로, 같은 원본이
  기기마다 같은 파일로 나오는지는 앱 연동 뒤에 확인한다.
- 같은 요청을 다시 보내도 사진은 1장만 저장된다. `Idempotency-Key`는 쓰지 않는다.

## 오류

앱은 상태 코드로 「다시 시도」 대상을 가린다(3.2.2 상세동작 5).

| 상태 | 코드 | 원인 | 다시 시도 |
| --- | --- | --- | --- |
| 400 | `COMMON_001` | 형식·용량·해상도 초과, 위치 정보 남음, `photo` 누락, UUID·날짜·시각 형식 오류, 날짜 없이 시각만 보냄, `capturedUtcOffset`이 `±HH:mm`(`-12:00`~`+14:00`)이 아님, `sourceType`이 네 값(대문자 정확히)이 아님 | 불가 |
| 401 | `AUTH_001` | 토큰 없음·만료·세션 없음 | 로그인 후 |
| 403 | `AUTH_002` | 번치 멤버가 아님(나간 멤버 포함) | 불가 |
| 403 | `AUTH_015` | 가입이 끝나지 않음 | 불가 |
| 404 | `RESOURCE_001` | 번치 없음 | 불가 |
| 409 | `PHOTO_001` | 번치 사진 1,000장 도달 (새 사진만, 합치기는 허용) | 불가 |
| 409 | `PHOTO_002` | 이 번치에서 오늘(`Asia/Seoul` 자정 기준) 100장 도달. 번치마다 따로 센다 | 불가 |
| 409 | `PHOTO_003` | 같은 `uploadBatchId`로 50장 도달 | 불가 |
| 503 | `PHOTO_007` | 이미지 저장소 일시 장애 | **가능** |

- 400의 구체적인 이유는 응답에 담지 않는다. 앱은 이유를 나누어 보여주지 않는다(3.2.2 상세동작 4).
- 한도를 넘는 사진만 거부하고 나머지는 저장한다. 예: 990장인데 20장을 올리면 10장은 `STORED`, 10장은 `409 PHOTO_001`이다.
- 업로드 도중 번치를 나가면 이후 요청은 `403 AUTH_002`다. 이미 올라간 사진의 정리는 번치 이탈 정리(3.6.1)가 맡는다.

## 서버 한도

- 요청 크기: 서버의 `multipart` 한도는 파일 30MB, 요청 31MB로, 사진 1장 30MiB 기준(3.2.2)과 맞춘다.
  한도를 넘으면 이미지 검사에 닿기 전에 `400 COMMON_001`로 거부된다. 이 설정은 서버의 모든 파일 업로드에
  적용되며, 가입 프로필 사진과 번치 이미지는 코드의 5MiB 검사를 그대로 유지한다.
- 동시 요청은 5개 이하를 권장한다. 서버는 번치 단위로 저장 구간을 직렬 처리하므로 동시에 보내도 한도와 중복은 깨지지 않는다.

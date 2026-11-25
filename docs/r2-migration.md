# Cloudinary 원본 전체 → R2 전환 (#213)

운영은 기존 `api.bunch.kr`과 프로젝트 `bunch`에서 새 빈 DB·Redis·`bunch-prod`로 시작한다.
기존 테스트 DB·Redis 볼륨과 Cloudinary 원본은 개발용으로 보존한다.
`dev.bunch.kr` 연결과 개발 앱 기동은 운영 전환 뒤에 진행할 수 있다.
파일 복사와 기존 DB 경로 갱신은 별도 관리 작업으로 수행한다.

## 저장 계약

- 앱 설정은 `R2_ENDPOINT`(버킷명 제외), `R2_BUCKET`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`다.
- 개발은 `bunch-dev`, 운영은 `bunch-prod`를 사용한다. 버킷은 private으로 유지한다.
- 사진은 `photos/<ID>/original.jpg`, `photos/<ID>/thumbnail.jpg`에 저장한다.
  신규 ID는 UUID이며 기존 자산은 ImageAsset ID를 사용한다.
- 프로필은 `profiles/<ID>/profile.jpg` 한 장을 저장한다. DB에는 `image_assets.object_key`만 저장한다.
- 원본 바이트와 Photo 해시는 유지한다. 사진 썸네일은 EXIF 방향을 반영한 최대 300px 중앙 정사각 JPEG 품질75다.
- 목록은 썸네일, 상세는 원본을 제공한다. 권한 검사 뒤 1800초 GET URL을 서명한다.

## 운영 우선 전환

1. #226을 먼저 반영하고 #225를 함께 적용한다. main CI에서 게시·수신한 같은 앱 이미지를 사용한다.
2. 기존 DB·Redis와 홈 설정의 복구 가능한 백업을 확보한다. 점검 시간에 기존 앱·Tunnel·DB·Redis를 정지한다.
3. 기존 `bunch-pgdata`·`bunch-redis-data` 볼륨을 보존하고 운영 DB·Redis를 새 빈 볼륨으로 기동한다.
4. 운영 DB 계정을 초기화하고 R2 앱을 기동한다. 빈 DB는 V53 다음에 V55와 원본 크기를 추가하는 V56을 적용한다.
   기존 사진 복사나 DB 경로 갱신은 빈 운영 DB에 실행하지 않는다.
5. 운영의 DB·Redis·R2 연결과 내부 health, 개발 토큰 API 제거을 확인한 뒤 기존 API Tunnel을 연다.
   개발 DNS와 테스트 앱 주소 변경은 이후에 진행한다.

현재 R2는 `V55__r2_image_objects.sql`을 사용한다. #217을 나중에 반영할 때는 아직 적용하지 않은 V54를
V56보다 큰 다음 사용 가능한 버전으로 바꾸고 V56이 추가한 width·height의 중복 추가를 제거한다.
R2 경로와 EXIF 기준 크기 저장을 유지하며 통합한다. 적용된 Flyway 파일은 수정하지 않는다.

## 기존 사진 직접 이전

2026-11-25 조회 기준 Cloudinary에는 원본 1,526개(JPEG 1,515개, PNG 11개)가 있고,
기존 DB의 자산 133개는 모두 업로드 JPEG 원본과 일치했다. 실제 복사 시 최신 목록을 다시 확인한다.

1. 기존 원본 전체 목록과 ImageAsset ID·public ID·URL을 보관한다. 원본을 다운로드해 `bunch-dev`에 복사한다.
   사진·번들 참조 자산은 원본과 위 규격의 썸네일을, 프로필 참조 자산은 프로필 파일을 준비한다.
   DB에서 참조하지 않는 원본도 `archive/`에 원래 형식으로 보관한다.
   DB에 남아 있는 미참조 자산은 V55가 허용하는 `archive/assets/<ID>/<SHA256>/original.<형식>` 경로를 사용한다.
2. 전체 원본 개수와 복사 목록을 대조하고 원본·R2 다운로드 바이트의 SHA-256을 비교한다.
   썸네일 해상도·방향과 이미지 열람도 확인한다.
3. 기존 DB를 개발 컨테이너에 연결하고 새 앱 기동 전에 다음 준비 SQL을 직접 실행한다.

   ```sql
   ALTER TABLE image_assets ADD COLUMN IF NOT EXISTS object_key TEXT;
   ```

4. 검증한 자산 ID별 R2 경로를 한 트랜잭션으로 `image_assets.object_key`에 입력한다.
   기존 ID·FK·사진 해시는 보존하고 누락 경로와 중복 경로가 없는지 확인한다.
   V56 적용 후 원본의 EXIF 회전 뒤 크기를 `photos.width`·`photos.height`에 채울 수 있다.
   기존 크기를 채우기 어렵다면 두 값은 `null`로 유지한다.
   복사되지 않은 파일의 경로를 DB에 먼저 입력하지 않는다.
5. 개발 R2 앱을 기동한다. V55는 자산별 경로를 검사하고 기존 Cloudinary 컬럼을 제거한다.
   기존 사진·프로필·번들 조회와 목록 썸네일·상세 원본·업로드·서명 만료를 검증한다.
6. 개발 DNS와 앱 주소를 연결한 뒤 실제 앱으로 확인한다. 원본 목록·복사 검증 기록·DB 백업은 성공 확인까지 보관한다.

기존 DB에 자산이 있으면 경로 갱신 전에 새 앱을 기동할 수 없다.
V55 이후 구버전 앱으로 복구할 때는 전환 전 DB 백업도 필요하다.
Cloudinary 원본 삭제와 계정 해지는 이전 성공을 확인한 뒤 별도로 진행한다.

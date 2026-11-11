# BUNCH 번들 A/B 테스트 화면 (#107)

예전에 사용한 `localhost:3300` 테스트 화면의 레이아웃·로컬 프록시·STOMP 전송을 재사용했다.
제목/날짜의 독점 편집권 시나리오는 #107의 CRDT·비독점 presence 계약으로 전환한다.
제품 서버의 보안 설정이나 배포 리소스에 테스트 페이지를 추가하지 않는다.

## 실행

```bash
cd /tmp/bunch-ut-107
BUNDLE_LAB_PORT=3300 bash tools/bundle-lab/start.sh
```

JDK21·Node/npm·Python3·Docker가 필요하다. 서버 JAR가 최신이면 재사용하고, 없거나
서버 소스가 바뀌었으면 bootJar를 만든다. CRDT 브라우저 패키지는 검증한
`@automerge/automerge@3.2.1` 하나이며 npm ci로 설치한다. 패키지는 로컬에서만 제공한다.

앱은 127.0.0.1:18080, 관리 포트는 18081, 화면은[localhost:3300](http://localhost:3300)이다.
명시한 포트가 사용 중이면 타 프로세스를 종료하지 않고 실패한다. BUNDLE_LAB_PORT를 생략하면
3300부터 빈 포트를 고른다. 원래 작업 경로와 다른 채팅의 실행 환경은 변경하지 않는다.

## 데이터와 실행 범위

자동 생성/재사용하는 **bunch-lab107-postgres**(25432)와 **bunch-lab107-redis**(26379)는
이 화면 전용이다. PostgreSQL은 `bunch_bundle_lab107_1129`, Redis는 DB 13만 사용한다.
최종 PR 순서에 맞춰 미게시 migration 번호를 바꾸면서 새 lab DB로 분리했다.
이전 번호가 적용된 `bunch_bundle_lab107` DB는 수정하거나 삭제하지 않는다.
시작 때 해당 PostgreSQL DB의 lab 행을 초기화한다. Redis는 FLUSHDB 없이 새 세션과 TTL을 쓴다.
다른 DB/기본 Redis DB 0/운영 환경을 사용하지 않는다. 백그라운드 작업자는 비활성화한다.

테스트용 가입 완료 회원 A/B와 같은 번치의 참여자, 원본 사진 8장, 번들 사진 6장을 준비한다.
원본 사진 ID 101~108과 번들 사진 ID 901~906은 서로 다르다. 제목은「제주 여행」,
revision 12/titleVersion 3/dateVersion 2에서 시작한다. 사진 107/108은 추가 실험용이다.
A는 OWNER, B는 MEMBER다. A가 제목/코멘트 등 필수 조건을 만족하면 최종 발행할 수 있다.
데이터를 처음부터 다시 시험하려면 실행을 종료하고 재시작한다. fixture 버튼은 현재 세션의
접속 정보를 다시 넣는 버튼이며 DB를 매번 초기화하지 않는다.

로컬 JWT는 실행할 때 만든 비밀값으로 서명하며 파일·로그에 저장하지 않는다. seed 출력은
파이프로 Node 메모리에만 전달한다. `/__lab/fixture`는 동일 Origin·loopback Host의 POST만
받는다. 브라우저 JWT 필드는 password이며 snapshot/trace/storage-state로 원문을 수집하지 않는다.
실제 소셜 로그인/사진 업로드/이미지 제공자는 검증하지 않는다.

## 직접 테스트

1. **로컬 A/B 데이터 불러오기**를 누르고 A/B 각각 연결한다.
2. 제목을 양쪽에서 동시에 수정한다. 편집권을 획득하지 않으며 CRDT 병합 결과가 양쪽에
   반영되는지 확인한다. 제목 입력은1초 무입력/blur에서 저장한다.
3. 제목/본인 코멘트 입력 중 글자·프로필 식별자 표시를 확인한다. 입력 중단 3초에 typing이
   사라지는지, VIEW는 입장만 갱신하는지 확인한다. 연결 신호 중단은최대15초다.
4. 날짜는 양쪽에서 다르게 저장하여 마지막 저장값을 확인한다. 사진은BundlePhoto ID로
   이동하며, 다른 사용자가 기준 사진을 삭제한 경우와 동일 틈·같은 사진의 이동도 시험한다.
   **장소 보내기** 영역에서 이름·주소를 입력하고 보내면 상대 화면에도 반영된다.
   작업을 교체·삭제·순서 이동으로 바꾸면 등록된 장소를 선택할 수 있다. 최대 3개이며,
   실제 장소 검색 대신 직접 입력으로 API를 테스트한다. 장소 식별값은 접힌 영역에서 수정한다.
5. 범위를 선택하고 최종 발행한다. 편집 중인 사람의 존재로 차단하지 않으며 최신 저장 내용과
   선택 범위를 함께 확정한다. 이후 초안 저장은거부되고 실제publishedBy를 확인할 수 있다.
6. 연결이 끊기면 읽기 전용이다. 재연결은 서버 snapshot으로 복구하고 미전송 입력을 버린다.

제목 바이너리 256KiB 제한·병합 후 40자 초과/발행 차단 등은 서버계약을 따른다.
#122참여자선택/#123픽/#124발행본범위/#125콕/#126검색은 이 브랜치에 통합하지 않았다.
실제 앱의 IME/커서·드래그 애니메이션·모바일OS background는별도검증이다.

## 검증과 종료

```bash
node --test tools/bundle-lab/protocol.test.mjs tools/bundle-lab/server.test.mjs
PYTHONDONTWRITEBYTECODE=1 python3 tools/bundle-lab/seed_test.py
```

Playwright CLI의 활성 `bundle-lab-107` 세션에서 `run-code --filename=tools/bundle-lab/browser-check.js`로
실제 A/B 요청·저장·수신을 검사한다. 브라우저 검사는 로컬 fixture만 수정하고 토큰을 출력하지 않는다.
실행 결과는 `docs/verification/107-bundle-lab.md`에 남긴다. 서버 본체 검증은 별도 #107 전체 빌드 기록을 따른다.

실행 터미널의 Ctrl-C 또는 다른 터미널에서 다음 명령으로 이 경로의 페이지·백엔드를 종료한다.

```bash
bash tools/bundle-lab/stop.sh
```

전용 DB 컨테이너는 재실행을 위해 남긴다. 종료할 때도 다른 경로의 프로세스/컨테이너는 건드리지 않는다.

# 제목 공동 편집 (#107)

최신 시트 4.3.3 J36·4.3.14 J47의 글자 병합과 40자 초과 상태를 따른다.
자체 CRDT를 만들지 않고 `org.automerge:automerge:0.0.9`의 TEXT 하나를 쓴다.
기존 STOMP·REST·번치→번들 잠금과 트랜잭션을 유지하며 별도 동기화 서버는 없다.

## 라이브러리 선택과 호환성

[공식 Java 릴리스](https://github.com/automerge/automerge-java/releases/tag/v0.0.9)는
JVM JNI 패키지와 Maven 좌표를 제공한다. 번들에 필요한 root `title` TEXT만 허용한다.
[공식 데이터 모델](https://automerge.org/docs/reference/documents/)의 JS 문자열은
공동 텍스트이며 [splice](https://automerge.org/automerge/api-docs/js/functions/splice.html)로 편집한다.
root의 문자열 대입·TEXT 교체는 사용하지 않는다. 다른 root 키·다중 충돌 TEXT·marks는 거부한다.

Maven JAR에는 macOS arm64/x64와 Linux GNU arm64/x64 바이너리가 포함된다.
프로젝트 Dockerfile은 Ubuntu Jammy 기반 JRE 21이다. 실제 검증 결과는 작업 기록에 남긴다.
프론트 의존성은 이 서버 저장소에 추가하지 않으며 JS 3.2.1과 바이너리 왕복을 별도로 검증한다.

## REST와 저장

`PATCH /api/v1/bunches/{bunchId}/bundles/{id}/title/crdt`

```json
{"state": "<Automerge.save 결과의 base64>"}
```

가입 완료·활성 번치 소속·DRAFT 참여자만 가능하다. mode/lease/version을 보내지 않는다.
현재 상태와 incoming 전체 저장 문서를 Automerge로 병합하고, 제목·바이너리·버전·revision을
한 트랜잭션에 저장한다. `X-Request-Id`는 선택 UUID이며 기존 envelope로 응답한다.

```json
{"success":true,"data":{"title":"부산 여행 1일차","titleCrdtState":"<base64>","titleVersion":3,"titleTooLong":false},"error":null}
```

snapshot에도 titleCrdtState/titleTooLong이 있다. 구 초안은 최초 잠금 조회에서 문서를
초기화해 저장한다. 같은 초기 TEXT ID를 공유해야 하므로 각 클라이언트가 새 문서를
만들지 않고 snapshot 바이너리를 load/clone한다. 발행본의 문서 상태는 반환하지 않는다.
TITLE 이벤트는 value/version 및 title/titleVersion/titleCrdtState/titleTooLong을 포함한다.
바이너리만 달라져도 병합 의존성이 바뀌므로 버전을 증가시킨다. 완전히 같은 재전송은 no-op이다.

256KiB를 넘는 입력/병합 상태, 손상된 바이너리, 다른 문서의 TEXT, 개행/NUL은 COMMON_001이다.
이 프로토콜은 full save 문서 chunk 하나만 받는다. incremental change·뒤에 붙인 chunk나
미해결 의존성 전송을 허용하지 않는다. 문서 chunk 길이·라이브러리 checksum/로드를 검사한다.
삭제·발행 뒤 저장은 기존 상태/접근 오류이며 참여 제외 뒤에는 FORBIDDEN이다.

raw CRDT의 문자 위치를 유지한다. 표시 제목만 NFC·trim하며 40자 초과 병합을 자르지 않는다.
발행 blocker TITLE_TOO_LONG으로 업로드를 막고 40자 이하로 수정하면 해제한다.
기존 `PUT /title`은 전환용이며 최대40자·expectedVersion을 검사하고 lease는 검사하지 않는다.
저장 시 같은 TEXT를 splice하여 기존 바이너리도 갱신한다.

## 클라이언트 전환

```javascript
let doc = Automerge.load(base64ToBytes(snapshot.titleCrdtState));
doc = Automerge.change(doc, draft => {
  Automerge.splice(draft, ['title'], index, deleteCount, insertedText);
});
// PATCH: {state: bytesToBase64(Automerge.save(doc))}
// 연결 중 원격 TITLE은 Automerge.merge(doc, Automerge.load(remoteBytes)).
```

실제 입력은 로컬에 즉시 반영한다. 저장은 1초 무입력/blur, 다른 멤버 표시에는 별도
비독점 typing을 사용한다. 서버 입력 순서가 달라도 라이브러리 병합 결과로 수렴한다.
연결 단절은 읽기 전용이며 재연결 시 서버 snapshot으로 문서를 교체한다.
서버 미반영 입력을 오프라인 큐로 보관·재생하지 않는다. 실제 2인 iOS/WebView 입력 보존·
IME·커서/선택 복구 검증은 서버 자동 테스트와 구분한다.

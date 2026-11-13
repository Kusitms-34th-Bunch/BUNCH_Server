# Automerge JS interoperability fixture

`title-js-3.2.1.automerge` is a synthetic document, not user data.
Created with official `org.automerge:automerge:0.0.9` (root title TEXT, `제주 여행`),
loaded using `@automerge/automerge@3.2.1`, cloned twice and spliced concurrently:

```javascript
const a = Automerge.change(Automerge.clone(doc), d => Automerge.splice(d, ['title'], 0, 2, '부산'));
const b = Automerge.change(Automerge.clone(doc), d => Automerge.splice(d, ['title'], 5, 0, ' 1일차'));
const state = Automerge.save(Automerge.merge(a, b));
```

Expected title: `부산 여행 1일차`. The JVM service test reads this exact JS saved file.
No frontend runtime dependency or offline store is part of the server application.

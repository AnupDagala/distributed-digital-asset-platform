# Dependency audit and patch policy

Spring Boot 3.5.16 supplies the main dependency BOM. The initial OSV audit of 125 resolved runtime artifacts reported 13 advisory matches. Rather than suppressing configuration-dependent findings, the project overrides compatible patch versions:

| Component | BOM/resolved version | Patched version | Advisory IDs |
| --- | --- | --- | --- |
| Log4j API | 2.24.3 | 2.25.5 | GHSA-qv9r-c865-cp47 |
| Jackson BOM | 2.21.4 | 2.21.5 | GHSA-5gvw-p9qm-jgwh, GHSA-5jmj-h7xm-6q6v, GHSA-mhm7-754m-9p8w |
| Tomcat | 10.1.55 | 10.1.59 | GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5 |
| Netty BOM | 4.1.135.Final | 4.1.137.Final | GHSA-c4c3-7fpv-j4q5, GHSA-fccg-mwvh-qqg4, GHSA-558v-64gr-wgg4 |
| LZ4 Java | 1.10.1 | 1.11.1 | GHSA-xx22-p4ch-683r |
| PostgreSQL JDBC | 42.7.11 | 42.7.12 | GHSA-j92g-9f8w-j867 |
| Commons Lang | 3.17.0 | 3.18.0 | GHSA-j288-q9x7-2f5v |

The PDFBox dependency excludes commons-logging because Spring supplies its logging bridge. These overrides are tracked in pom.xml, not hidden in a developer machine's Maven settings. Remove an override only when the parent BOM supplies a suitable version and the full tests/audit pass.

Reproduce the audit with Maven 3.9+, Java 21 and Node.js 20+:

```sh
mvn -B -ntp org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree -Dscope=runtime -DoutputType=json -DoutputFile=target/dependency-tree.json
node scripts/audit-dependencies.mjs
```

The script uses the [OSV batch API](https://google.github.io/osv.dev/post-v1-querybatch/), handles pagination, fails on incomplete responses and writes target/dependency-audit.json. It sends only public Maven coordinates and versions. It returns failure if any advisory matches. It does not scan container OS packages, JDK images or build/test tooling, and does not establish exploitability. See docs/VERIFICATION.md for the executed result. The optional Maven security profile runs OWASP Dependency-Check separately and needs NVD network access; its execution is not implied by the OSV result.

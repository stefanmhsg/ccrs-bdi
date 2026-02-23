# CCRS BDI Repository

---

### Run

Defaults to [ccrs_bdi.jcm](ccrs_bdi.jcm):
```powershell
gradle run
```

To run a specific JaCaMo configuration file, use:

##### Depth-First Search (DFS) Baseline Agent:

* [dfs_baseline.asl](src\agt\dfs_baseline.asl) implements a Depth-First Search to navigate the maze. This is a possible solution without considering any CCRS features. Can handle 'unlock' actions.

```powershell
gradle run "-Pjcm=dfs_baseline.jcm"
```

##### DFS Baseline Agent extended with opportunistic CCRS:

* [dfs_opportunistic_ccrs.asl](src\agt\dfs_opportunistic_ccrs.asl) extends the DFS baseline agent with opportunistic CCRS features. It defaults to DFS but prioritizes options at every step based on opportunistic CCRS outcomes.

```powershell
gradle run "-Pjcm=dfs_opportunistic_ccrs.jcm"
```

##### DFS Baseline Agent extended with opportunistic and contingency CCRS:

* [dfs_ccrs.asl](src\agt\dfs_ccrs.asl) extends the DFS baseline agent with opportunistic CCRS features. It defaults to DFS but prioritizes options at every step based on opportunistic CCRS outcomes. Additionally, it incorporates contingency CCRS features that provide a set of strategies to guide the agent's actions in case of predefined situations.

```powershell
gradle run "-Pjcm=dfs_ccrs.jcm"
```

---

### Mindinspector URL:
http://192.168.68.53:3272/

### Resources

* [JaCaMo Docs](https://jacamo-lang.github.io/doc)

* [Jason Docs](https://jason-lang.github.io/)
* [Jason API](https://jason-lang.github.io/api/jason/stdlib/package-summary.html#package.description)
* [Unification of Annotations](https://jason-lang.github.io/jason/tech/annotations.html)
* [Plan patterns](https://jason-lang.github.io/jason/tech/patterns.html)

* [Hypermedea Github](https://github.com/Hypermedea/hypermedea)
    * [Artifact](https://github.com/Hypermedea/hypermedea/blob/master/hypermedea-lib/src/main/java/org/hypermedea/HypermedeaArtifact.java)
* [Hypermedea API](https://hypermedea.github.io/javadoc/hypermedea/latest/)
    * [rdf](https://hypermedea.github.io/javadoc/hypermedea/latest/org/hypermedea/ct/rdf/package-summary.html)

* [LDFU](https://linked-data-fu.github.io/#faq)
### Run

Defaults to [ccrs_bdi.jcm](ccrs_bdi.jcm):
```powershell
gradle run
```

To run a specific JaCaMo configuration file, use:
```powershell
gradle run "-Pjcm=test.jcm"
```

### Agents

#### Up-to-date
* [baseline_agent.asl](src\agt\baseline_agent.asl) implements a Depth-First Search to navigate the maze. This is a possible solution without considering any CCRS features. Can handle 'unlock' actions.
* [random_agent.asl](src\agt\random_agent.asl) implements an agent that explores the maze randomly. It can handle 'unlock' actions and eventually finds the exit.
* [minimal_agent.asl](src\agt\minimal_agent.asl) implements a minimal agent that only explores the maze randomly. It cannot handle 'unlock' actions. Succeeds only if unlock actions are already performed by other agents.


#### Rather outdated

#### Customizations
* [BRF in AgCcrs.java](src\agt\ccrs\AgCcrs.java) customizes the belief revision function to handle CCRS-specific beliefs.

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
# Run every JUnit test in one test file:
#   make RelevanceScorerTest
#   make RelevanceScorerTest.java
#   make src/test/java/com/litroute/scoring/RelevanceScorerTest.java

.PHONY: FORCE
FORCE: ;

# Keep the catch-all rule below from trying to rebuild this file.
Makefile: ;

%: FORCE
	mvn test -Dtest='$(basename $(notdir $@))'

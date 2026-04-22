JAVAC=javac
JAVAFLAGS=
SRC_DIR=src
SOURCES=$(wildcard $(SRC_DIR)/*.java)
CLASSES=$(SOURCES:.java=.class)

all: $(CLASSES)

$(SRC_DIR)/%.class: $(SRC_DIR)/%.java
	$(JAVAC) $(JAVAFLAGS) $(SOURCES)

clean:
	rm -f $(SRC_DIR)/*.class

run-sender: all
	java -cp $(SRC_DIR) TCPend $(ARGS)

run-receiver: all
	java -cp $(SRC_DIR) TCPend $(ARGS)

.PHONY: all clean run-sender run-receiver

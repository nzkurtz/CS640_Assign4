all:
	javac -d src src/*.java

clean:
	rm -f src/*.class *.class

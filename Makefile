all:
	javac -d . src/*.java

clean:
	rm -f src/*.class

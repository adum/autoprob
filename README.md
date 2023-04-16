# autoprob
automatically find and extract go problems from games

more documentation coming soon!

# quickstart

1) download katago: https://github.com/lightvector/KataGo
2) download some weights. i recommend the b15 network for speed: https://media.katagotraining.org/uploaded/networks/models/kata1/kata1-b15c192-s1672170752-d466197061.txt.gz
3) make sure you have Java installed. compile the autoprob source or run existing jars (when available)
4) download a gson jar: https://github.com/google/gson
5) validate it works with a sample test file. a simple command line might be:

`path_to\config.properties katago=path_to\katago.exe kata.config=path_to\analysis_example.cfg kata.model=path_to\weights.txt.gz path=sample_games\2019-04-01-123_m198.sgf turn=198 forceproblem=true`

you will have to pass in the gson jar. this should pop up a Java window with a detected problem from this game. replace the paths with paths to your katago executable, config file, and weights.

if you are having problems, try passing `kata.debugprint=true` on the command line also

# Configuration

The general approach is to take configuration parameters from the properties file, optionally overridden by using the same names on the command line (with the format `name=value`). The first command line parameter must be a path to the properties file, and this is the only unnamed parameter. A default file is provided. There are a lot of options!

# Developer IDE

This has been loaded in both IntelliJ and Eclipse. Some project files may exist and work.

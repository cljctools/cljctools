# edit
library for editing clojure code

## why

- an editor extension like cljctools/mult needs to format/color/edit clojure source files
- as of now (March 2021) every editor extension implements their own logic to select forms, color brackets, format etc.
- that shouldn't be the case - there's nothing editor/extension specific about editing clojure/edn code, it should be generic
- with edit library we should be able to create an generic edit process, pass editor specifics in options (abstracted via edit.protocols and streams,like tools.reader StringReader)
- right now a basic operation  - given source text/stream and position, select current s-expression - is not implemented in any lib, every extension does their own dance with travsersing delimiters and selecting forms; in words of Gandalf - "Thranduil, this is madness!"
- the choice right now is - to drop another extension-specific dir in cljctools/mult and start tying the knot, or explicitly create a generic dependency and use it
- second option, please
- library should have spec and decribe the state of document as data, then extension imports it and using that data applies editor specific operations as needed
- what should work
  - form selection and editing (app using the lib asks "select current form at cursor", lib gives back a zipper or string, "move current form into the upper one" - new state of doc that can be applied to the underlying doc)
  - formatting as we type 
  - color tokens, brackets (as data decribing the document)
- it's about representing doc as zipper and data, and notifying app of changes: get source code/streams -> change internal state of edit -> notify of changes
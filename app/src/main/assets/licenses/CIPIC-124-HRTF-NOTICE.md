# CIPIC subject 124 HRTF data

The packaged `hrtf/cipic_124.sofa` data asset is copied without modification
from the Steam Audio v4.8.1 SDK/source distribution at
`steamaudio/core/data/hrtf/cipic_124.sofa` (upstream source-tree path:
`core/data/hrtf/cipic_124.sofa`). FLACtify does not claim that this exact file
has been independently traced to a particular CIPIC dataset release or that it
is public domain.

- SHA-256: `c28ff4a874ac889ec0c5885ca524762a70d56984232ff7aadcd9c15d32e1cfb6`
- Upstream source: <https://github.com/ValveSoftware/steam-audio/blob/v4.8.1/core/data/hrtf/cipic_124.sofa>
- SDK source archive: <https://github.com/ValveSoftware/steam-audio/releases/download/v4.8.1/steamaudio_4.8.1.zip>
- The applicable CIPIC attribution and terms are the CIPIC HRTF Database notice
  in the exact Steam Audio v4.8.1 `THIRDPARTY.md`, retained as
  `steam-audio-4.8.1-THIRDPARTY.md` in generated APK assets. Its CIPIC section
  identifies Copyright (c) 2001 The Regents of the University of California
  and grants use/reproduction on the conditions stated verbatim below.

## CIPIC notice from Steam Audio v4.8.1 `THIRDPARTY.md`

Sponsorship

  This work was supported by the National Science Foundation under Grants
  IRI-9619339 and ITR-0086075. Any opinions, findings and conclusions or
  recommendations expressed in this material are those of the authors, and
  do not necessarily reflect the views of the National Science Foundation.

Copyright

Copyright (c) 2001 The Regents of the University of California. All Rights Reserved

Disclaimer

THE REGENTS OF THE UNIVERSITY OF CALIFORNIA MAKE NO REPRESENTATION OR
WARRANTIES WITH RESPECT TO THE CONTENTS HEREOF AND SPECIFICALLY DISCLAIM ANY
IMPLIED WARRANTIES OR MERCHANTABILITY OR FITNESS FOR ANY PARTICULAR PURPOSE.

Further, the Regents of the University of California reserve the right to
revise this software and/or documentation and to make changes from time to
time in the content hereof without obligation of the Regents of the University
of California to notify any person of such revision or change.

Use of Materials

The Regents of the University of California hereby grant users permission to
reproduce and/or use materials available therein for any purpose- educational,
research or commercial. However, each reproduction of any part of the
materials must include the copyright notice, if it is present.

In addition, as a courtesy, if these materials are used in published research,
this use should be acknowledged in the publication. If these materials are
used in the development of commercial products, the Regents of the University
of California request that written acknowledgment of such use be sent to:

     CIPIC- Center for Image Processing and Integrated Computing
     University of California
     1 Shields Avenue
     Davis, CA 95616-8553

The asset is an unmodified SOFA container. At rates not present in Steam Audio's
default HRTF table, Steam Audio loads this SOFA data and resamples the HRTF to
the stream sampling rate. FLACtify does not resample music PCM.

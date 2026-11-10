# Deploying files

*Deploy to…* copies files of the project to a folder, on the machine where the server runs or on another machine.

## Using it

1. Right-click a file or a directory in the project view (the project root deploys everything) and choose
   **Deploy to…**.
2. Type the destination, then **Deploy**:
   - a folder of the server's disk: `/var/www/site`, `C:\sites\demo`, or `dist` (a relative folder is relative to the
     project);
   - a folder of another machine: `host:folder`, `user@host:/var/www/site`, `alias:~/site`. `host` is what `ssh`
     accepts, so an alias of `~/.ssh/config` brings its user, port, key and `ProxyJump`. A relative remote folder is
     relative to the SSH user's home; `~` and `~/…` are that home.
3. The dialog stays open and says how many files (and bytes) were copied, or why nothing was. The destination is
   offered again next time.

When the project is on a remote server (see [REMOTE.md](REMOTE.md)), the files are copied *from that server*: a
local destination means a folder of the server's disk, and `host:folder` is reached by `ssh` run on the server.

## Saved deployments

The **Deploy** button of the toolbar opens the list of the deployments saved for the project, as a table: the
**Name**, the **Trigger** (*Manual* or *Automatic*), the **Target** (the destination; one reached over SSH has an
*SSH* badge) and the **Files** sent (their names, one per line; the pointer over them shows each full path and where it lands). Each
row has a **Deploy** button, and a **⋯** menu with *Edit*, *Duplicate* (a new deployment filled like this one, named
`… copy`, saved only on Save) and *Delete*. A line under the table says where they go (*All targets are Local.*, or how
many go over SSH). A click beside the popup closes the list, never a form being filled.

**New deployment** opens a form: the **Name**, the **Destination**, the **Folders**, the **Trigger**, then *Cancel* and
*Save* (which stay in sight while the fields scroll).

The destination is chosen first, with a switch between **Local folder** (a path of the server's disk) and **SSH
server**; a badge next to its title says **Local** or **Remote · host**. For SSH the form has a menu of the hosts of the
`~/.ssh/config` of the machine that runs `ssh`, that is the server (`deploy/sshHosts`, resolved with `ssh -G` like the
remote dialog; `Include` files are followed): pick one (a search field narrows the list, the name and the address are
in two columns, and the menu closes on a pick or a click elsewhere), then type the folder on it (empty: its home
folder). A host that is not in the configuration can be typed as `user@host`. Users never have to write the
`host:folder` form themselves.

The folders are a table with two columns, *In the project* (the folder or file) and *On the destination* (the path
under the destination where it lands), and under each row where it ends up (`→ user@host:/var/www/docs`), following
the destination as you type. The path on the destination starts as the folder's own path and follows it; type another
one when the layout of the repository and the one of the destination differ (`src/main` → `app/code`, `docs` →
nothing, to merge it into the destination itself, a file to another name), and **↺** in the field goes back to the
same path. *Add folder* adds a row, *×* removes one, and *Add selected in project view* adds the entry selected in the
tree. A path outside the project, or one that leaves the destination (`..`, absolute), is flagged as you type it.
*Deploy to…* in the project view also offers **Save as deployment** once it ran. Each deployment has a trigger:

- **Manual**: it runs when you press its **Deploy** button, and sends all its folders.
- **Automatic**: it runs by itself as soon as a file of its folders changes, whoever changes it (the editor, a
  terminal, a build, `git checkout`). Only the changed files are sent, together when they change in a burst (the
  deployment waits 0.4 s after the last change, 3 s at most). A file deleted is not deleted on the destination. Its
  line shows `Automatic: Deployed …` or why it failed, and it keeps working. A destination inside the project
  (`dist`) is not watched, so a deployment never deploys what it wrote. Changes made while the IDE is closed, or
  while the link to a remote server was down, are not sent: press **Deploy** to send everything.

The list is kept by the server, per project, in `~/.kotlin-editor/deployments`, so a project opened from another IDE
or reached over SSH has the same deployments. Automatic deployments run while the project is open in the IDE.

## What it does

- Each source lands at the path chosen for it under the destination, by default its own place in the project:
  deploying `web/css` to `/srv/app` writes `/srv/app/web/css/…`; with the path `static`, `/srv/app/static/…`. Two
  folders landing in the same place are merged, the later one replacing the files they have in common. In automatic
  mode, a changed file lands at the path of its folder followed by its place in that folder.
- Existing files are **replaced**; files of the destination that are not in the project are **left alone**. Nothing
  is ever deleted.
- `.git` directories are not copied.
- Permissions (the executable bit), times and symbolic links are kept; a link is copied as a link, not followed.
- The project and its repository are only read.
- A source outside the project, a source deleted meanwhile and a destination inside a deployed directory are
  refused before anything is written.
- Running it again is safe and gives the same result: a deployment whose answer was lost to a network failure is
  not run twice.

## Over SSH

The server runs, for each folder, `tar -c … | ssh -o BatchMode=yes host "mkdir -p … && tar -x -C …"` (a file is sent
with `cat`, which lets it land under another name; a large folder goes in several archives): OpenSSH on the server's machine, a
POSIX `sh` and `tar` on the host. Authentication is by key or `ssh-agent` only (no password prompt), and the host key
must already be in `~/.ssh/known_hosts`, as for [remote development](REMOTE.md). A failure shows what `ssh` or `tar`
printed, for example `Permission denied (publickey).`.

A host name is checked before it is passed to `ssh`: only letters, digits, `.`, `_`, `-` and `@` are accepted, and
it cannot start with `-`.

## Protocol

`deploy/profilesLoad` and `deploy/profilesSave` (mutating, replaces the list) keep the saved deployments.
`deploy/run` (mutating, so deduplicated by request id on reconnection) takes `root`, `items` (each a `source` entry
and the `target` path under the destination) and `destination` (`path`, and `host` or null), and answers
`{files, bytes}`. A saved deployment is a list of `folders` (`source`, `target`); a list of `sources` saved before the
targets existed is read as folders landing at their own path. The protocol version is 14.

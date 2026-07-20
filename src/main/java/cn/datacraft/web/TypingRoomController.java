package cn.datacraft.web;

import cn.datacraft.typing.TypingDtos.JoinResponse;
import cn.datacraft.typing.TypingDtos.PublicRoomView;
import cn.datacraft.typing.TypingDtos.RoomView;
import cn.datacraft.typing.TypingRoomService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.List;

@RestController
@RequestMapping("/api/tools/typing/rooms")
public class TypingRoomController {
    private final TypingRoomService rooms;

    public TypingRoomController(TypingRoomService rooms) {
        this.rooms = rooms;
    }

    @GetMapping
    public List<PublicRoomView> list() {
        return rooms.listRooms();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoomView create(@Valid @RequestBody CreateRoomRequest request, Authentication authentication) {
        return rooms.createRoom(request.name, authentication.getName());
    }

    @GetMapping("/{roomId}")
    public RoomView detail(@PathVariable String roomId,
                           @RequestHeader(value = "X-Room-Token", required = false) String token,
                           Authentication authentication) {
        return rooms.roomView(roomId, principalName(authentication), token);
    }

    @PostMapping("/{roomId}/join")
    public JoinResponse join(@PathVariable String roomId, @Valid @RequestBody JoinRoomRequest request,
                             HttpServletRequest servletRequest) {
        return rooms.join(roomId, request.displayName, request.inviteCode, servletRequest.getRemoteAddr());
    }

    @PostMapping("/{roomId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable String roomId,
                      @RequestHeader("X-Room-Token") String token) {
        rooms.leave(roomId, token);
    }

    @PostMapping("/{roomId}/start")
    public RoomView start(@PathVariable String roomId, @Valid @RequestBody StartBattleRequest request,
                          Authentication authentication) {
        return rooms.start(roomId, authentication.getName(), request.leftMemberId,
                request.rightMemberId, request.articleId);
    }

    @PostMapping("/{roomId}/reset")
    public RoomView reset(@PathVariable String roomId, Authentication authentication) {
        return rooms.reset(roomId, authentication.getName());
    }

    @PostMapping("/{roomId}/finish")
    public RoomView finish(@PathVariable String roomId, Authentication authentication) {
        return rooms.manualFinish(roomId, authentication.getName());
    }

    @PostMapping("/{roomId}/invite/rotate")
    public RoomView rotateInvite(@PathVariable String roomId, Authentication authentication) {
        return rooms.rotateInviteCode(roomId, authentication.getName());
    }

    @DeleteMapping("/{roomId}/members/{memberId}")
    public RoomView kick(@PathVariable String roomId, @PathVariable String memberId,
                         Authentication authentication) {
        return rooms.kick(roomId, authentication.getName(), memberId);
    }

    @DeleteMapping("/{roomId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@PathVariable String roomId, Authentication authentication) {
        rooms.close(roomId, authentication.getName());
    }

    private static String principalName(Authentication authentication) {
        return authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken ? null : authentication.getName();
    }

    public static final class CreateRoomRequest {
        @NotBlank @Size(min = 2, max = 30)
        public String name;
    }

    public static final class JoinRoomRequest {
        @NotBlank @Size(max = 16)
        public String displayName;
        @NotBlank @Size(min = 6, max = 6)
        public String inviteCode;
    }

    public static final class StartBattleRequest {
        @NotBlank public String leftMemberId;
        @NotBlank public String rightMemberId;
        @NotBlank public String articleId;
    }
}

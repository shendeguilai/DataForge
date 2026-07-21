package cn.datacraft.web;

import cn.datacraft.quiz.QuizDtos.CatalogView;
import cn.datacraft.quiz.QuizDtos.JoinResponse;
import cn.datacraft.quiz.QuizDtos.RoomView;
import cn.datacraft.quiz.QuizRoomService;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.*;
import java.util.List;

@RestController
@RequestMapping("/api/tools/quiz")
public class QuizRoomController {
    private final QuizRoomService rooms;

    public QuizRoomController(QuizRoomService rooms) {
        this.rooms = rooms;
    }

    @GetMapping("/catalog")
    public CatalogView catalog() {
        return rooms.catalog();
    }

    @PostMapping("/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public RoomView create(@Valid @RequestBody CreateRoomRequest request, Authentication authentication) {
        return rooms.createRoom(request.name, authentication.getName(), request.categories,
                request.difficulties, request.questionCount, request.buzzSeconds);
    }

    @GetMapping("/rooms/{roomCode}")
    public RoomView detail(@PathVariable String roomCode,
                           @RequestHeader(value = "X-Room-Token", required = false) String token,
                           Authentication authentication) {
        return rooms.roomView(roomCode, principalName(authentication), token);
    }

    @PostMapping("/rooms/{roomCode}/join")
    public JoinResponse join(@PathVariable String roomCode, @Valid @RequestBody JoinRoomRequest request,
                             HttpServletRequest servletRequest) {
        return rooms.join(roomCode, request.displayName, request.inviteCode, servletRequest.getRemoteAddr());
    }

    @PostMapping("/rooms/{roomCode}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable String roomCode, @RequestHeader("X-Room-Token") String token) {
        rooms.leave(roomCode, token);
    }

    @PostMapping("/rooms/{roomCode}/rounds/next")
    public RoomView next(@PathVariable String roomCode, Authentication authentication) {
        return rooms.prepareNext(roomCode, authentication.getName());
    }

    @PostMapping("/rooms/{roomCode}/rounds/question")
    public RoomView choose(@PathVariable String roomCode, @Valid @RequestBody ChooseQuestionRequest request,
                           Authentication authentication) {
        return rooms.chooseCurrent(roomCode, authentication.getName(), request.questionId);
    }

    @PostMapping("/rooms/{roomCode}/rounds/open")
    public RoomView open(@PathVariable String roomCode, Authentication authentication) {
        return rooms.openBuzz(roomCode, authentication.getName());
    }

    @PostMapping("/rooms/{roomCode}/rounds/judge")
    public RoomView judge(@PathVariable String roomCode, @Valid @RequestBody JudgeRequest request,
                          Authentication authentication) {
        return rooms.judge(roomCode, authentication.getName(), request.result);
    }

    @PostMapping("/rooms/{roomCode}/rounds/reveal")
    public RoomView reveal(@PathVariable String roomCode, Authentication authentication) {
        return rooms.reveal(roomCode, authentication.getName());
    }

    @PostMapping("/rooms/{roomCode}/invite/rotate")
    public RoomView rotateInvite(@PathVariable String roomCode, Authentication authentication) {
        return rooms.rotateInviteCode(roomCode, authentication.getName());
    }

    @DeleteMapping("/rooms/{roomCode}/members/{memberId}")
    public RoomView kick(@PathVariable String roomCode, @PathVariable String memberId,
                         Authentication authentication) {
        return rooms.kick(roomCode, authentication.getName(), memberId);
    }

    @DeleteMapping("/rooms/{roomCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@PathVariable String roomCode, Authentication authentication) {
        rooms.close(roomCode, authentication.getName());
    }

    private static String principalName(Authentication authentication) {
        return authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken ? null : authentication.getName();
    }

    public static final class CreateRoomRequest {
        @NotBlank @Size(min = 2, max = 30)
        public String name;
        public List<String> categories;
        public List<String> difficulties;
        @Min(1) @Max(50)
        public int questionCount = 20;
        @Min(5) @Max(120)
        public Integer buzzSeconds = 15;
    }

    public static final class JoinRoomRequest {
        @NotBlank @Size(max = 16)
        public String displayName;
        @NotBlank @Pattern(regexp = "[0-9]{6}")
        public String inviteCode;
    }

    public static final class ChooseQuestionRequest {
        @NotBlank @Pattern(regexp = "J[0-9]{3}")
        public String questionId;
    }

    public static final class JudgeRequest {
        @NotBlank @Pattern(regexp = "(?i)CORRECT|WRONG")
        public String result;
    }
}
